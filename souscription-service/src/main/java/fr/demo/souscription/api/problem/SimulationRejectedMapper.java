package fr.demo.souscription.api.problem;

import fr.demo.souscription.api.dto.FieldErrors;
import fr.demo.souscription.integration.SimulationRejectedException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Parametres refuses par le moteur de calcul distant, rendus en <b>400</b>
 * {@code application/problem+json} (W6-T02).
 *
 * <p>Le source refusait ces entrees localement par une {@code IllegalArgumentException} et le
 * formulaire affichait le verdict a cote du champ fautif. Le calcul etant desormais distant, le
 * verdict appartient au fournisseur : son tableau {@code errors} est relaye tel quel, afin que la
 * classe d'erreur rendue a l'utilisateur soit inchangee. Un refus de parametres n'est jamais
 * converti en 5xx et n'est jamais reessaye (condition non rejouable du contrat de resilience).
 */
@Provider
public class SimulationRejectedMapper implements ExceptionMapper<SimulationRejectedException> {

    private static final Logger LOG = Logger.getLogger(SimulationRejectedMapper.class);

    /** Code utilise lorsque le fournisseur n'a relaye aucun verdict par champ. */
    public static final String SIMULATION_PARAMETERS_REJECTED = "SIMULATION_PARAMETERS_REJECTED";

    @Override
    public Response toResponse(SimulationRejectedException exception) {
        LOG.debugf("Parametres de simulation refuses par le fournisseur ; champs=%d",
                exception.relayed().size());
        Problem problem = new Problem("urn:horizon:validation-failed", "horizon.validation.failed",
                400, null, null, Problems.correlationId(), errors(exception.relayed()));
        return Response.status(400).type(Problems.PROBLEM_JSON).entity(problem).build();
    }

    private static List<Problem.FieldError> errors(List<FieldErrors.FieldError> relayed) {
        if (relayed.isEmpty()) {
            return List.of(new Problem.FieldError("simulation", "simulation.parameters.rejected",
                    SIMULATION_PARAMETERS_REJECTED));
        }
        List<Problem.FieldError> errors = new ArrayList<>();
        for (FieldErrors.FieldError error : relayed) {
            errors.add(new Problem.FieldError(error.field(), error.message(), error.code()));
        }
        return List.copyOf(errors);
    }
}
