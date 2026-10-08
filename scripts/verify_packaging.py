#!/usr/bin/env python3
"""Inspect built distribution contents, installed artifacts, and preserved notices.

Run after Maven clean install and scripts/smoke.py. Uses only the standard library;
SERDEPROOF_MAVEN_REPO overrides the usual ~/.m2/repository for local-only caches.
"""
from pathlib import Path
import hashlib,json,os,sys,zipfile
root=Path(__file__).resolve().parents[1]
repository=Path(os.environ.get('SERDEPROOF_MAVEN_REPO', str(Path.home()/'.m2/repository')))
version='0.1.0'
modules=['serdeproof-api','serdeproof-core','serdeproof-cli','demo-jackson2','demo-jackson3']
archive=root/'serdeproof-dist/target/serdeproof-0.1.0.zip'
stale=[]
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
    names=z.namelist(); files=[n for n in names if not n.endswith('/')]
    assert len(names)==len(set(names))
    assert not any(any(x in Path(n).parts for x in ['.tools','.local-repository','.git','target','reports','__pycache__']) for n in names)
    prefix='serdeproof-0.1.0/'
    required=['LICENSE','NOTICE','THIRD_PARTY_NOTICES.md','README.md','examples/release-demo.json','source/pom.xml','source/mvnw','source/mvnw.cmd','source/.mvn/wrapper/maven-wrapper.properties','source/serdeproof-dist/src/assembly/distribution.xml']
    for m in modules:
        required.extend(['lib/'+m+'-'+version+s+'.jar' for s in ['', '-sources','-javadoc']])
        required.append('source/'+m+'/pom.xml')
    assert not [n for n in required if prefix+n not in names]
    assert (z.getinfo(prefix+'source/mvnw').external_attr>>16)&0o111
    if prefix+'source/.gitignore' not in names: stale.append('source/.gitignore missing')
    timestamps=sorted({i.date_time for i in z.infolist()})
    assert timestamps==[(2026,10,8,0,0,0)]
    for n in files:
        if n.startswith(prefix+'source/'):
            current=root/n[len(prefix+'source/'):]
            if current.is_file() and current.read_bytes()!=z.read(n): stale.append(n[len(prefix):])
    for m in modules:
        assert z.read(prefix+'lib/'+m+'-'+version+'.jar')==(root/(m+'/target/'+m+'-'+version+'.jar')).read_bytes()
records=[]
for m in modules:
    path=root/(m+'/target/'+m+'-'+version+'.jar')
    with zipfile.ZipFile(path) as z:
        assert z.testzip() is None
        names=z.namelist(); assert len(names)==len(set(names))
        for f in ['LICENSE','NOTICE','THIRD_PARTY_NOTICES.md']:
            if z.read('META-INF/serdeproof/'+f)!=(root/f).read_bytes(): stale.append(m+'/META-INF/serdeproof/'+f)
        manifest=z.read('META-INF/MANIFEST.MF').decode()
        main='io.github.rad1092.serdeproof.cli.Main' if m=='serdeproof-cli' else 'io.github.rad1092.serdeproof.api.AdapterMain' if m.startswith('demo') else None
        if main: assert 'Main-Class: '+main in manifest
        notices={n:hashlib.sha256(z.read(n)).hexdigest() for n in names if ('license' in n.lower() or 'notice' in n.lower()) and not n.endswith('/')}
        upstreamChecked=[]
        if main:
            version3=m=='demo-jackson3'; group='tools/jackson/core' if version3 else 'com/fasterxml/jackson/core'; v='3.2.3' if version3 else '2.22.3'
            dependencies=[repository/group/artifact/v/(artifact+'-'+v+'.jar') for artifact in ['jackson-core','jackson-databind']]
            dependencies.append(repository/'com/fasterxml/jackson/core/jackson-annotations/2.22/jackson-annotations-2.22.jar')
            for upstream in dependencies:
                with zipfile.ZipFile(upstream) as dep:
                    for n in dep.namelist():
                        if ('license' in n.lower() or 'notice' in n.lower()) and not n.endswith('/'):
                            assert dep.read(n) in z.read(n),(m,upstream.name,n)
                upstreamChecked.append(upstream.name)
    installed=repository/('io/github/rad1092/serdeproof/'+m+'/'+version+'/'+m+'-'+version+'.jar')
    assert path.read_bytes()==installed.read_bytes()
    records.append({'artifact':m,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'installedBytesMatch':True,'upstreamLicenseBytesPreservedFrom':upstreamChecked,'noticeEntrySha256':notices,'mainClass':main})
    print(m+': installed bytes, manifest, project and upstream notices inspected')
record={'schemaVersion':1,'artifactVersion':version,'distributionSha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'zipFiles':len(files),'zipEntriesUnique':True,'excludedPathsAbsent':True,'requiredSourceAndExamplesPresent':True,'sourceWrapperExecutable':True,'fixedZipTimestamp':list(timestamps[0]),'distributionLibrariesMatchBuildOutputs':True,'staleSourceOrNoticeCopies':stale,'jars':records,'passed':not stale}
(root/'target/verification').mkdir(parents=True,exist_ok=True)
(root/'target/verification/packaging.json').write_text(json.dumps(record,indent=2)+'\n')
print('Archive inspection:',len(files),'files;',len(stale),'stale source/notice copies;',('PASS' if not stale else 'REBUILD REQUIRED'))
if stale: sys.exit(1)
