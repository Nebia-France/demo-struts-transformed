package fr.demo.souscription.gateway.error;

import fr.demo.souscription.gateway.api.CatalogueResource;
import fr.demo.souscription.gateway.api.ConfirmationResource;
import fr.demo.souscription.gateway.api.CsrfTokenResource;
import fr.demo.souscription.gateway.api.DraftResource;
import fr.demo.souscription.gateway.api.SimulationResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * HTTP methods declared by the public resources for a request path, used to fill the {@code Allow}
 * header of a 405 raised by Quarkus REST (which throws {@code NotAllowedException} without it).
 *
 * <p>Built once from the {@code @Path} and HTTP-method annotations of the five resource classes, so
 * the header always reflects the resource model. {@code HEAD} is added wherever {@code GET} is
 * declared, as Quarkus REST serves it implicitly.
 */
@ApplicationScoped
public class DeclaredMethods {

    private static final List<Class<?>> RESOURCES = List.of(CatalogueResource.class,
            ConfirmationResource.class, CsrfTokenResource.class, DraftResource.class,
            SimulationResource.class);

    private static final Pattern PARAM = Pattern.compile("\\{[^}/]+}");

    private final Map<Pattern, Set<String>> routes = new LinkedHashMap<>();

    public DeclaredMethods() {
        for (Class<?> resource : RESOURCES) {
            String base = resource.getAnnotation(Path.class).value();
            for (Method method : resource.getDeclaredMethods()) {
                String verb = verb(method);
                if (verb == null) {
                    continue;
                }
                Path sub = method.getAnnotation(Path.class);
                String template = sub == null ? base : join(base, sub.value());
                Set<String> verbs = routes.computeIfAbsent(toPattern(template), p -> new TreeSet<>());
                verbs.add(verb);
                if ("GET".equals(verb)) {
                    verbs.add("HEAD");
                }
            }
        }
    }

    /** Declared methods for {@code path}, comma-separated; {@code null} if no route matches. */
    public String allowFor(String path) {
        if (path == null) {
            return null;
        }
        String normalised = path.length() > 1 && path.endsWith("/")
                ? path.substring(0, path.length() - 1) : path;
        List<String> matched = new ArrayList<>();
        routes.forEach((pattern, verbs) -> {
            if (pattern.matcher(normalised).matches()) {
                verbs.stream().filter(v -> !matched.contains(v)).forEach(matched::add);
            }
        });
        return matched.isEmpty() ? null : String.join(", ", new TreeSet<>(matched));
    }

    private static String verb(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            HttpMethod http = annotation.annotationType().getAnnotation(HttpMethod.class);
            if (http != null) {
                return http.value();
            }
        }
        return null;
    }

    private static String join(String base, String sub) {
        String left = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return sub.startsWith("/") ? left + sub : left + "/" + sub;
    }

    private static Pattern toPattern(String template) {
        StringBuilder regex = new StringBuilder();
        int last = 0;
        java.util.regex.Matcher m = PARAM.matcher(template);
        while (m.find()) {
            regex.append(Pattern.quote(template.substring(last, m.start()))).append("[^/]+");
            last = m.end();
        }
        regex.append(Pattern.quote(template.substring(last)));
        return Pattern.compile(regex.toString());
    }
}
