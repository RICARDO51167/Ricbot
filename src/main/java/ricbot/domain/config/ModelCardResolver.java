package ricbot.domain.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ricbot.infra.config.Config;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Loads user cards before bundled cards; exact patterns win over wildcard patterns. */
public final class ModelCardResolver {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    public Optional<ModelCard> resolve(Config config, String provider, String model) {
        List<ModelCard> user = new ArrayList<>();
        if (config != null && config.getModelCards() != null) {
            for (String raw : config.getModelCards().getPaths()) user.addAll(readPath(Path.of(raw)));
        }
        Optional<ModelCard> match = match(user, provider, model);
        return match.isPresent() ? match : match(bundled(), provider, model);
    }

    private static Optional<ModelCard> match(List<ModelCard> cards, String provider, String model) {
        String p = provider != null ? provider.trim() : "";
        String m = model != null ? model.trim() : "";
        return cards.stream().filter(card -> card.provider().equalsIgnoreCase(p))
                .filter(card -> glob(card.modelPattern()).matcher(m).matches())
                .sorted(Comparator.comparing((ModelCard card) -> card.modelPattern().contains("*") ? 1 : 0)
                        .thenComparing(ModelCard::id)).findFirst();
    }

    private static List<ModelCard> bundled() {
        try (InputStream input = ModelCardResolver.class.getResourceAsStream("/model-cards/v1/cards.json")) {
            if (input == null) return List.of();
            return read(MAPPER.readTree(input));
        } catch (Exception failure) { throw new IllegalStateException("cannot load bundled model cards", failure); }
    }

    private static List<ModelCard> readPath(Path path) {
        try {
            if (!Files.exists(path)) throw new IllegalArgumentException("model card path does not exist: " + path);
            if (Files.isDirectory(path)) {
                try (var files = Files.list(path)) {
                    List<ModelCard> values = new ArrayList<>();
                    for (Path file : files.filter(item -> item.getFileName().toString().endsWith(".json")).sorted().toList()) {
                        values.addAll(read(MAPPER.readTree(file.toFile())));
                    }
                    return values;
                }
            }
            return read(MAPPER.readTree(path.toFile()));
        } catch (Exception failure) { throw new IllegalArgumentException("cannot read model cards from " + path, failure); }
    }

    private static List<ModelCard> read(JsonNode node) throws Exception {
        List<ModelCard> values = new ArrayList<>();
        if (node.isArray()) for (JsonNode item : node) values.add(MAPPER.treeToValue(item, ModelCard.class));
        else values.add(MAPPER.treeToValue(node, ModelCard.class));
        return List.copyOf(values);
    }
    private static Pattern glob(String value) {
        String expression = Pattern.quote(value).replace("*", "\\E.*\\Q");
        return Pattern.compile("^" + expression + "$", Pattern.CASE_INSENSITIVE);
    }
}
