package example;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Stand-in for an existing service mapper factory; reuse yours and its modules here. */
public final class ServiceJson {
    private ServiceJson() { }
    public static ObjectMapper mapper() {
        return JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    }
}
