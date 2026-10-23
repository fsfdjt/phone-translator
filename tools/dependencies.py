"""Pinned Maven coordinates for the Gradle-independent Windows build."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEPS = ROOT / '.toolchain' / 'aliyun-dependencies'
RUNTIME = [
    'com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar',
    'com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar',
    'org/jetbrains/kotlin/kotlin-stdlib/1.9.10/kotlin-stdlib-1.9.10.jar',
    'org/jetbrains/kotlin/kotlin-stdlib-common/1.9.10/kotlin-stdlib-common-1.9.10.jar',
    'org/jetbrains/kotlin/kotlin-stdlib-jdk7/1.9.10/kotlin-stdlib-jdk7-1.9.10.jar',
    'org/jetbrains/kotlin/kotlin-stdlib-jdk8/1.9.10/kotlin-stdlib-jdk8-1.9.10.jar',
    'org/jetbrains/annotations/13.0/annotations-13.0.jar',
]
TEST = [
    'junit/junit/4.13.2/junit-4.13.2.jar',
    'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar',
    'org/json/json/20240303/json-20240303.jar',
]


def fetch(coordinate):
    target = DEPS / coordinate.rsplit('/', 1)[1]
    if target.exists() and zipfile.is_zipfile(target):
        return target
    temporary = target.with_suffix('.download')
    # TLS verification remains enabled. This mirror is reachable on this host.
    for repository in ('https://maven.aliyun.com/repository/central/',
                       'https://repo.maven.apache.org/maven2/'):
        result = subprocess.run(['curl.exe', '-fsSL', '--connect-timeout', '10', '--max-time', '60',
                                 '-o', str(temporary), repository + coordinate])
        if result.returncode == 0 and zipfile.is_zipfile(temporary):
            temporary.replace(target)
            print('Downloaded ' + target.name, flush=True)
            return target
    raise RuntimeError('Cannot download ' + coordinate)


def dependencies():
    DEPS.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=4) as pool:
        files = list(pool.map(fetch, RUNTIME + TEST))
    return files[:len(RUNTIME)], files[len(RUNTIME):]


if __name__ == '__main__':
    dependencies()
