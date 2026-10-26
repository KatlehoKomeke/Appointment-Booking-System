/**
 * Error responses: every failure becomes an RFC 9457 {@code application/problem+json} body with the
 * request's correlation id, and with a stable code for every failure the API returns on purpose.
 *
 * <p>{@link za.co.fynbos.branchbook.shared.problem.ProblemCode} lists the codes and {@link
 * za.co.fynbos.branchbook.shared.problem.ProblemException} is what modules throw. {@link
 * za.co.fynbos.branchbook.shared.problem.ProblemDetailHandler} writes the responses for failures
 * inside Spring MVC and {@link za.co.fynbos.branchbook.shared.problem.ErrorDispatchController} for
 * those before it; both build them through {@code Problems} and log unexpected failures through
 * {@code LoggableFailure}, which withholds exception messages. The codes and their meaning are
 * documented in docs/errors.md.
 *
 * <p>{@link za.co.fynbos.branchbook.shared.problem.AnsweredProblem} notes on the request which code
 * it was answered with, so a module can count its requests by outcome without classifying failures
 * a second time.
 */
package za.co.fynbos.branchbook.shared.problem;
