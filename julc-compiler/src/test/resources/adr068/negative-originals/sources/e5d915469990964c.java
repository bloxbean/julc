@SpendingValidator
class BadValidator {
    @Entrypoint
    static boolean validate(int a, int b) {
        try { return true; } catch (Exception e) { return false; }
    }
}
