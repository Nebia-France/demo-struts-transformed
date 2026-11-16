package fr.demo.souscription.gateway.client;

/**
 * A downstream answer in the 5xx range, i.e. a failure of the called service itself.
 *
 * <p>Split out of {@link DownstreamHttpException} so the fault tolerance policies of
 * {@code docs/resilience-policies.md} can name it: a downstream 5xx is what a bounded retry on an
 * idempotent read must absorb and what must count towards the circuit breaker, whereas a downstream
 * 400 or 422 is a verdict on the request and must do neither. The public status mapping is
 * unchanged, being inherited from the parent (503 relayed, any other 5xx becoming 502).
 */
public class DownstreamServerException extends DownstreamHttpException {

    private static final long serialVersionUID = 1L;

    public DownstreamServerException(int downstreamStatus, String message) {
        super(downstreamStatus, message);
    }
}
