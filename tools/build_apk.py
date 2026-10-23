"""Build a fresh debug APK and run protocol tests without a Gradle daemon.

Requires the existing .toolchain JDK 17 and Android SDK 35; no old APK is reused.
Run: python tools/build_apk.py
"""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import tempfile
import zipfile
from dependencies import ROOT, dependencies


def run(*args):
    # Native Android tools on this Windows host cannot read Chinese absolute paths.
    command = [str(arg.relative_to(ROOT)) if isinstance(arg, Path) and arg.is_relative_to(ROOT)
               else str(arg) for arg in args]
    subprocess.run(command, cwd=ROOT, check=True)


def main():
    runtime, test = dependencies()
    java = ROOT / '.toolchain/jdk/jdk-17.0.20.1+1/bin'
    sdk = ROOT / '.toolchain/android-sdk'
    android = sdk / 'platforms/android-35/android.jar'
    buildtools = sdk / 'build-tools/35.0.0'
    work = Path(tempfile.mkdtemp(prefix='aliyun-build-', dir=ROOT / '.toolchain'))
    source = ROOT / 'app/src/main/java/com/example/facetofacetranslator'
    tests = ROOT / 'app/src/test/java/com/example/facetofacetranslator'
    testclasses = work / 'testclasses'
    testclasses.mkdir()
    # These classes use only Java and org.json, so they can be tested on the host.
    tested = [source / (name + '.java') for name in ('AliyunRpcSigner', 'ParaformerProtocol', 'TranslatorClient', 'GatewayProtocol', 'Wav')]
    testcp = os.pathsep.join(map(str, test))
    run(java / 'javac.exe', '--release', '8', '-encoding', 'UTF-8', '-cp', testcp,
        '-d', testclasses, *tested, *tests.glob('*.java'))
    run(java / 'java.exe', '-cp', str(testclasses) + os.pathsep + testcp,
        'org.junit.runner.JUnitCore', 'com.example.facetofacetranslator.CloudProtocolTest', 'com.example.facetofacetranslator.GatewayProtocolTest', 'com.example.facetofacetranslator.WavTest')

    resources = work / 'compiled.zip'
    run(buildtools / 'aapt2.exe', 'compile', '--dir', ROOT / 'app/src/main/res', '-o', resources)
    manifest = work / 'AndroidManifest.xml'
    original = (ROOT / 'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8-sig')
    manifest.write_text(original.replace('<manifest ', '<manifest package="com.example.facetofacetranslator" ', 1), encoding='utf-8')
    config = (ROOT / 'app/build.gradle').read_text(encoding='utf-8-sig')
    version = re.search(r'versionName\s+[\'\"]([^\'\"]+)', config).group(1)
    code = re.search(r'versionCode\s+(\d+)', config).group(1)
    generated = work / 'generated'
    generated.mkdir()
    base = work / 'resources.apk'
    run(buildtools / 'aapt2.exe', 'link', '-I', android, '--manifest', manifest,
        '--java', generated, '--min-sdk-version', '26', '--target-sdk-version', '34',
        '--version-code', code, '--version-name', version, '-o', base, resources)
    classes = work / 'classes'
    classes.mkdir()
    classpath = os.pathsep.join(map(str, [android] + runtime))
    run(java / 'javac.exe', '--release', '8', '-encoding', 'UTF-8', '-cp', classpath,
        '-d', classes, *source.glob('*.java'), *generated.rglob('*.java'))
    appjar = work / 'app.jar'
    run(java / 'jar.exe', 'cf', appjar, '-C', classes, '.')
    dex = work / 'dex'
    dex.mkdir()
    run(java / 'java.exe', '-cp', buildtools / 'lib/d8.jar', 'com.android.tools.r8.D8',
        '--min-api', '26', '--lib', android, '--output', dex, appjar, *runtime)
    unsigned = work / 'unsigned.apk'
    with zipfile.ZipFile(base) as originalzip, zipfile.ZipFile(unsigned, 'w', zipfile.ZIP_DEFLATED) as result:
        names = set()
        for entry in originalzip.infolist():
            result.writestr(entry, originalzip.read(entry.filename))
            names.add(entry.filename)
        for item in dex.glob('*.dex'):
            result.write(item, item.name)
            names.add(item.name)
        # Include OkHttp's public suffix table and other runtime resources.
        for jar in runtime:
            with zipfile.ZipFile(jar) as dep:
                for entry in dep.infolist():
                    name = entry.filename
                    if entry.is_dir() or name.endswith('.class') or name.startswith('META-INF/') or name in names:
                        continue
                    result.writestr(name, dep.read(name))
                    names.add(name)
    aligned = work / 'aligned.apk'
    run(buildtools / 'zipalign.exe', '-f', '4', unsigned, aligned)
    output = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    output.parent.mkdir(parents=True, exist_ok=True)
    # Keep the previous installed debug certificate for an in-place upgrade.
    key = ROOT / '.toolchain/gradle-home/debug.keystore'
    if not key.exists():
        run(java / 'keytool.exe', '-genkeypair', '-keystore', key, '-storepass', 'android',
            '-keypass', 'android', '-alias', 'androiddebugkey', '-dname', 'CN=Android Debug,O=Android,C=US',
            '-keyalg', 'RSA', '-validity', '10000')
    run(java / 'java.exe', '-jar', buildtools / 'lib/apksigner.jar', 'sign', '--ks', key,
        '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', output, aligned)
    run(java / 'java.exe', '-jar', buildtools / 'lib/apksigner.jar', 'verify', '--verbose', '--print-certs', output)
    run(buildtools / 'zipalign.exe', '-c', '4', output)
    run(buildtools / 'aapt.exe', 'dump', 'badging', output)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    (output.parent / 'app-debug.apk.sha256').write_text(digest + '  app-debug.apk\n', encoding='ascii')
    metadata = {
        'version': 3, 'artifactType': {'type': 'APK', 'kind': 'Directory'},
        'applicationId': 'com.example.facetofacetranslator', 'variantName': 'debug',
        'elements': [{'type': 'SINGLE', 'filters': [], 'attributes': [], 'versionCode': int(code),
                      'versionName': version, 'outputFile': output.name}], 'minSdkVersionForDexing': 26,
    }
    (output.parent / 'output-metadata.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
    print('APK: ' + str(output))
    print('Build workspace: ' + str(work))


if __name__ == '__main__':
    main()
