# ffm-check - `sokar-ffm-check`

Checks that every FFM downcall a test makes is registered for the native image, so a call that works on the JVM does
not fail only in the native executable. It is not a test framework of its own.
