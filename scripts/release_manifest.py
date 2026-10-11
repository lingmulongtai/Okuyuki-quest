"""Generate verifiable release metadata without substituting CI for device acceptance."""
import hashlib,json,os,sys
from pathlib import Path
root=Path(sys.argv[1])
apks=list(root.glob('*.apk'))
assert len(apks)==1
metadata={
    "version":os.environ['GITHUB_REF_NAME'],
    "commit":os.environ['GITHUB_SHA'],
    "protocolVersion":1,
    "hardwareTested":False,
    "physicalAcceptance":"pending: Galaxy S25 Ultra + Quest 3S",
    "transport":"authenticated local HTTP; TLS planned for v1.0",
    "apk":apks[0].name,
    "sha256":hashlib.sha256(apks[0].read_bytes()).hexdigest(),
}
(root/'release-manifest.json').write_text(json.dumps(metadata,indent=2)+"\n",encoding='utf-8')
