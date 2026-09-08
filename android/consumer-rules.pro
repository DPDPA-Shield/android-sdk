# No consumer ProGuard/R8 rules needed yet: this SDK has no reflection-based
# serialization surfaces a consumer's obfuscator would need to be told to
# keep (kotlinx.serialization generates its own consumer rules via its own
# Gradle plugin metadata), and every public API is a plain class/interface
# that survives default shrinking. Add rules here if that changes.
