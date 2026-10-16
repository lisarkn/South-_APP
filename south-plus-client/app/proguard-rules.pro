# Tink (via EncryptedSharedPreferences) references optional Error Prone annotations.
# These are static-analysis annotations, not runtime implementations. Keep this
# list restricted to the four missing annotation types reported by R8.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
