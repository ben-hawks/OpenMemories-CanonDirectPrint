# Vendored libraries

| File | Source | License |
|------|--------|---------|
| `openmemories-framework.jar` | [ma1co/OpenMemories-Framework](https://github.com/ma1co/OpenMemories-Framework) `framework`, jitpack build of commit `f8df3504d8` | MIT |
| `openmemories-stubs.jar` | [ma1co/OpenMemories-Framework](https://github.com/ma1co/OpenMemories-Framework) `stubs`, jitpack build of commit `f8df3504d8` | MIT |

The framework jar is bundled into the APK. The stubs jar is `compileOnly`: the
real `com.sony.scalar.*` classes are provided by the camera firmware at runtime.

They are vendored (instead of pulled from jitpack at build time) because the
upstream project is no longer maintained and its Gradle build no longer runs on
jitpack, so a reproducible build should not depend on the cached artifacts
staying online. To refresh them:

```sh
curl -L -o openmemories-framework.jar https://jitpack.io/com/github/ma1co/OpenMemories-Framework/framework/-SNAPSHOT/framework--SNAPSHOT.jar
curl -L -o openmemories-stubs.jar     https://jitpack.io/com/github/ma1co/OpenMemories-Framework/stubs/-SNAPSHOT/stubs--SNAPSHOT.jar
```
