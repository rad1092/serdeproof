package example;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.rad1092.serdeproof.api.AdapterResult;
import io.github.rad1092.serdeproof.api.RejectedInputException;
import io.github.rad1092.serdeproof.api.SerializerAdapter;

import java.util.LinkedHashMap;
import java.util.Map;

/** The integration code: compile once against each service's dependency graph. */
public final class OrderAdapter implements SerializerAdapter {
    private final ObjectMapper productionMapper = ServiceJson.mapper();
    private final ObjectMapper observationMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public OrderAdapter() { }

    @Override
    public AdapterResult evaluate(String type, byte[] input, byte[] configuration) throws Exception {
        if (!type.equals("order")) throw new IllegalArgumentException("Unsupported fixture type");
        if (configuration.length != 0) {
            var config = observationMapper.readTree(configuration);
            if (config == null || !config.isObject() || !config.isEmpty())
                throw new IllegalArgumentException("This adapter has no external configuration");
        }
        Order order;
        try {
            order = productionMapper.readValue(input, Order.class);
        } catch (InvalidDefinitionException error) {
            throw error;
        } catch (JsonProcessingException error) {
            throw new RejectedInputException();
        }
        if (order == null) throw new RejectedInputException();
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("id", order.id());
        observation.put("total", order.total() == null ? null : order.total().stripTrailingZeros().toPlainString());
        observation.put("status", order.status() == null ? null : order.status().name());
        return new AdapterResult(productionMapper.writeValueAsBytes(order), observationMapper.writeValueAsBytes(observation));
    }
}
