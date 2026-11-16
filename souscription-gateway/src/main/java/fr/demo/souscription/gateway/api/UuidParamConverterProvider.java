package fr.demo.souscription.gateway.api;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.UUID;

/**
 * Converts the client-supplied {@code draftId} path parameter (W3-T01).
 *
 * <p>The default Jakarta REST behaviour turns a conversion failure on a path parameter into a 404,
 * which would be indistinguishable from an unknown but well-formed draft identifier. A malformed
 * identifier is a structural input error, so it is rejected as a 400
 * {@code application/problem+json} by the mappers of the error package.
 */
@Provider
public class UuidParamConverterProvider implements ParamConverterProvider {

    private static final ParamConverter<UUID> CONVERTER = new ParamConverter<>() {
        @Override
        public UUID fromString(String value) {
            if (value == null) {
                return null;
            }
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException("Identifiant non conforme au format UUID");
            }
        }

        @Override
        public String toString(UUID value) {
            return value == null ? null : value.toString();
        }
    };

    @SuppressWarnings("unchecked")
    @Override
    public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType,
                                              Annotation[] annotations) {
        return UUID.class.equals(rawType) ? (ParamConverter<T>) CONVERTER : null;
    }
}
