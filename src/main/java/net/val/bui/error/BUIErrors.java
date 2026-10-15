package net.val.bui.error;

/** Creates consistent diagnostics for invalid API arguments, resources and runtime state. */
public final class BUIErrors {

    /**
     * Prevents construction of the diagnostic utility.
     */
    private BUIErrors() {}

    /**
     * Formats a diagnostic as {@code [CLASS] Name [@method()] -> problem -> Possible solution: recovery}. Pass the
     * class and method where the failure is detected.
     *
     * @param owner owning class, instance or resource module
     * @param method method name without parentheses
     * @param problem specific failure description
     * @param solution actionable recovery suggestion
     * @return the formatted class/method/problem/recovery diagnostic
     */
    public static String message(Class<?> owner, String method, String problem, String solution) {
        return "[CLASS] " + owner.getSimpleName() + " [@" + method + "()] -> " + problem
                + " -> Possible solution: " + solution;
    }

    /**
     * Creates an IllegalArgumentException for invalid API input using the standard bUI diagnostic format.
     *
     * @param owner owning class, instance or resource module
     * @param method method name without parentheses
     * @param problem specific failure description
     * @param solution actionable recovery suggestion
     * @return the contextual argument exception, ready to be thrown
     */
    public static IllegalArgumentException argument(Class<?> owner, String method, String problem, String solution) {
        return new IllegalArgumentException(message(owner, method, problem, solution));
    }

    /**
     * Creates an IllegalStateException for invalid resources or runtime state using the standard bUI diagnostic
     * format.
     *
     * @param owner owning class, instance or resource module
     * @param method method name without parentheses
     * @param problem specific failure description
     * @param solution actionable recovery suggestion
     * @return the contextual state exception, ready to be thrown
     */
    public static IllegalStateException state(Class<?> owner, String method, String problem, String solution) {
        return new IllegalStateException(message(owner, method, problem, solution));
    }

    /**
     * Checks a required API value and returns it unchanged; a null value produces a contextual
     * IllegalArgumentException rather than an unlabelled null-pointer failure.
     *
     * @param value value or state to inspect or assign
     * @param owner owning class, instance or resource module
     * @param method method name without parentheses
     * @param parameter required argument name
     * @param <T> required value type
     * @return the supplied value unchanged
     */
    public static <T> T requireNonNull(T value, Class<?> owner, String method, String parameter) {
        if (value == null) {
            throw argument(owner, method, parameter + " must not be null", "Provide a non-null " + parameter + ".");
        }
        return value;
    }
}
