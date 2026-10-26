package si.ogrodje.metaos.keycloak;

import java.util.List;
import org.keycloak.Config;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.events.Details;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.provider.ProviderConfigProperty;

/**
 * Remembers a sign-in, as "Remember me" on Keycloak's login form does, so Keycloak's sign-in cookie (KEYCLOAK_IDENTITY)
 * outlives the browser: it lasts as long as a remembered session may (ssoSessionMaxLifespanRememberMe), where it would
 * otherwise go when the browser quits, and the user with it. Sign-ins through Discord and Google never see that form, so
 * this is the last step of their post-login flows (keycloak/approval_gate.sh), which run before Keycloak makes the
 * session, and sets the auth note the form would have. Only while the realm has Remember me on.
 *
 * Java rather than Kotlin, so Keycloak needs no Kotlin library beside it. docker/keycloak.Dockerfile builds it into
 * Keycloak's image.
 */
public final class RememberMe implements AuthenticatorFactory {
    public static final String ID = "metaos-remember-me";

    private static final Authenticator STEP = new Authenticator() {
        @Override
        public void authenticate(AuthenticationFlowContext context) {
            if (context.getRealm().isRememberMe()) {
                context.getAuthenticationSession().setAuthNote(Details.REMEMBER_ME, "true");
            }
            context.success();
        }

        // It shows no form, so nothing comes back to it
        @Override
        public void action(AuthenticationFlowContext context) {
            context.success();
        }

        @Override
        public boolean requiresUser() {
            return false;
        }

        @Override
        public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
            return true;
        }

        @Override
        public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        }

        @Override
        public void close() {
        }
    };

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return STEP;
    }

    @Override
    public String getDisplayType() {
        return "Remember me";
    }

    @Override
    public String getHelpText() {
        return "Remembers the sign-in, as Remember me on the login form does, while the realm has Remember me on";
    }

    @Override
    public String getReferenceCategory() {
        return null;
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of();
    }

    @Override
    public Requirement[] getRequirementChoices() {
        return new Requirement[] {Requirement.REQUIRED, Requirement.DISABLED};
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }
}
