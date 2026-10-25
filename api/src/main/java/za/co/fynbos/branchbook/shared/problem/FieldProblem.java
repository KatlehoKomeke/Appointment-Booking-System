package za.co.fynbos.branchbook.shared.problem;

import org.jspecify.annotations.Nullable;

/**
 * One entry of a problem's {@code errors[]}: what is wrong with one field, parameter or header. It
 * names the field and the rule it broke, and never quotes the value, which could be personal data.
 *
 * @param field the request field, parameter or header with the problem, for example {@code
 *     customer.mobile}
 * @param code the rule it broke, for example {@code PATTERN} or {@code MOBILE_NUMBER_REQUIRED}
 * @param message a short explanation that never quotes the value
 */
public record FieldProblem(@Nullable String field, String code, @Nullable String message) {}
