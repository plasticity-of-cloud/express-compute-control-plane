package ai.codriverlabs.eksdx.tenant.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.quarkus.jackson.ObjectMapperCustomizer;
import io.quarkus.amazon.lambda.http.model.ApiGatewayAuthorizerContext;
import jakarta.inject.Singleton;
import java.io.IOException;

/**
 * API Gateway Function URLs with IAM auth send the "iam" authorizer context field
 * as a JSON object, but the Quarkus model declares it as String, causing
 * MismatchedInputException. This customizer registers a mix-in that tolerates both.
 */
@Singleton
public class ApiGatewayAuthorizerContextCustomizer implements ObjectMapperCustomizer {

    @Override
    public void customize(ObjectMapper mapper) {
        mapper.addMixIn(ApiGatewayAuthorizerContext.class, AuthorizerContextMixin.class);
    }

    abstract static class AuthorizerContextMixin {
        @JsonDeserialize(using = ObjectToStringDeserializer.class)
        abstract void setIam(String iam);
    }

    static class ObjectToStringDeserializer extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            return switch (p.currentToken()) {
                case START_OBJECT, START_ARRAY -> p.readValueAsTree().toString();
                default -> p.getValueAsString();
            };
        }
    }
}
