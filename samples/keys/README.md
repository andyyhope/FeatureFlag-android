# Debug-only signing keys

These two keystores sign the sample apps' debug builds. Their passwords are written down in this
repository, so anyone can sign anything with them. **Never use either key for anything real**:
not for a release, not for an app you ship, and not as a certificate a `knownSigner` permission
trusts.

| File | Alias | Store and key password | Certificate | Signs |
|---|---|---|---|---|
| `shared-debug.keystore` | `sample` | `android` | `CN=FeatureFlag Sample (debug only)` | the host's and the companion's `debug` builds |
| `untrusted.keystore` | `untrusted` | `android` | `CN=FeatureFlag Sample untrusted (debug only)` | the companion's `untrusted` build only |

They are committed, rather than generated on each machine, so that "signed with the same key"
holds on every machine and every CI runner: Android's own `~/.android/debug.keystore` differs
from one machine to the next. The untrusted key exists to show what a companion signed with any
other key sees: `PermissionDenied`, with both certificates' SHA-256 digests.

To make them again, with Android Studio's JDK:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
"$JAVA_HOME/bin/keytool" -genkeypair -keystore samples/keys/shared-debug.keystore -storetype PKCS12 \
  -storepass android -keypass android -alias sample -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=FeatureFlag Sample (debug only)"
"$JAVA_HOME/bin/keytool" -genkeypair -keystore samples/keys/untrusted.keystore -storetype PKCS12 \
  -storepass android -keypass android -alias untrusted -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=FeatureFlag Sample untrusted (debug only)"
```

A new shared key changes the certificate both apps are signed with, so uninstall both sample
apps before installing builds signed with it.
