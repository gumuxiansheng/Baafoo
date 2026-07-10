package com.baafoo.server.auth;

import com.baafoo.core.model.User;
import com.baafoo.server.storage.StorageService;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AuthServiceTest {

    private StorageService storage;

    @Before
    public void setUp() {
        storage = mock(StorageService.class);
    }

    // --- isAuthEnabled ---

    @Test
    public void isAuthEnabledReturnsTrue() {
        AuthService svc = new AuthService(storage, null, true, false, null);
        assertTrue(svc.isAuthEnabled());
    }

    @Test
    public void isAuthEnabledReturnsFalse() {
        AuthService svc = new AuthService(storage, null, false, false, null);
        assertFalse(svc.isAuthEnabled());
    }

    // --- authenticate ---

    @Test
    public void authenticateAuthDisabledReturnsAdmin() {
        AuthService svc = new AuthService(storage, null, false, false, null);
        AuthService.AuthResult result = svc.authenticate(null, null, "10.0.0.1");
        assertTrue(result.isSuccess());
        assertEquals("admin", result.getRole());
    }

    @Test
    public void authenticateLocalBypassReturnsAdmin() {
        AuthService svc = new AuthService(storage, null, true, true, null);
        AuthService.AuthResult result = svc.authenticate(null, null, "127.0.0.1");
        assertTrue(result.isSuccess());
        assertEquals("admin", result.getRole());
    }

    @Test
    public void authenticateWithNoCredentialsReturnsFailure() {
        AuthService svc = new AuthService(storage, null, true, false, null);
        AuthService.AuthResult result = svc.authenticate(null, null, "10.0.0.1");
        assertFalse(result.isSuccess());
    }

    @Test
    public void authenticateWithInvalidBearerTokenReturnsFailure() {
        AuthService svc = new AuthService(storage, null, true, false, null);
        AuthService.AuthResult result = svc.authenticate("Bearer invalid-token", null, "10.0.0.1");
        assertFalse(result.isSuccess());
    }

    @Test
    public void authenticateWithValidApiKeyReturnsRole() {
        Map<String, String> apiKeys = new HashMap<>();
        apiKeys.put("valid-key", "developer");
        AuthService svc = new AuthService(storage, null, true, false, apiKeys);

        AuthService.AuthResult result = svc.authenticate(null, "valid-key", "10.0.0.1");

        assertTrue(result.isSuccess());
        assertEquals("developer", result.getRole());
    }

    @Test
    public void authenticateWithUnknownApiKeyFallsBackToStorage() {
        User user = new User();
        user.setRole("tester");
        user.setUsername("testuser");
        when(storage.getUserByApiKey("unknown-key")).thenReturn(user);

        AuthService svc = new AuthService(storage, null, true, false, null);
        AuthService.AuthResult result = svc.authenticate(null, "unknown-key", "10.0.0.1");

        assertTrue(result.isSuccess());
        assertEquals("tester", result.getRole());
        assertEquals("testuser", result.getUsername());
    }

    @Test
    public void authenticateWithInvalidApiKeyReturnsFailure() {
        when(storage.getUserByApiKey("bad-key")).thenReturn(null);

        AuthService svc = new AuthService(storage, null, true, false, null);
        AuthService.AuthResult result = svc.authenticate(null, "bad-key", "10.0.0.1");

        assertFalse(result.isSuccess());
    }

    // --- login ---

    @Test
    public void loginAuthDisabledReturnsAdmin() {
        AuthService svc = new AuthService(storage, null, false, false, null);
        AuthService.LoginResult result = svc.login("any", "any", null);

        assertTrue(result.isSuccess());
        assertEquals("admin", result.getRole());
        assertNotNull(result.getToken());
    }

    @Test
    public void loginWithUnknownUserReturnsFailure() {
        when(storage.getUserByUsername("unknown")).thenReturn(null);

        AuthService svc = new AuthService(storage, null, true, false, null);
        AuthService.LoginResult result = svc.login("unknown", "pass", null);

        assertFalse(result.isSuccess());
    }

    @Test
    public void loginWithWrongPasswordReturnsFailure() {
        User user = new User();
        AuthService svc = new AuthService(storage, null, true, false, null);
        String hash = svc.hashPassword("correct-password");
        user.setPasswordHash(hash);
        when(storage.getUserByUsername("testuser")).thenReturn(user);

        AuthService.LoginResult result = svc.login("testuser", "wrong-password", null);

        assertFalse(result.isSuccess());
    }

    @Test
    public void loginWithValidCredentialsSucceeds() {
        User user = new User();
        user.setRole("developer");
        AuthService svc = new AuthService(storage, null, true, false, null);
        String hash = svc.hashPassword("ValidPass1!");
        user.setPasswordHash(hash);
        when(storage.getUserByUsername("testuser")).thenReturn(user);

        AuthService.LoginResult result = svc.login("testuser", "ValidPass1!", null);

        assertTrue(result.isSuccess());
        assertEquals("developer", result.getRole());
        assertNotNull(result.getToken());
        verify(storage).updateUserLastLogin("testuser");
    }

    @Test
    public void loginWithExpiryCapsAtMax() {
        User user = new User();
        user.setRole("tester");
        AuthService svc = new AuthService(storage, null, true, false, null);
        String hash = svc.hashPassword("ValidPass1!");
        user.setPasswordHash(hash);
        when(storage.getUserByUsername("testuser")).thenReturn(user);

        AuthService.LoginResult result = svc.login("testuser", "ValidPass1!", 999999999L);

        assertTrue(result.isSuccess());
        assertNotNull(result.getToken());
    }

    // --- hashPassword + verifyPassword ---

    @Test
    public void hashAndVerifyPasswordRoundTrip() {
        AuthService svc = new AuthService(storage, null, true, false, null);
        String hash = svc.hashPassword("MyPass123!");
        assertTrue(svc.verifyPassword("MyPass123!", hash));
        assertFalse(svc.verifyPassword("WrongPass!", hash));
    }

    @Test
    public void verifyPasswordWithMalformedHashReturnsFalse() {
        AuthService svc = new AuthService(storage, null, true, false, null);
        assertFalse(svc.verifyPassword("pass", "invalid-hash"));
        assertFalse(svc.verifyPassword("pass", "only-one-part"));
    }

    // --- generateApiKey ---

    @Test
    public void generateApiKeyStartsWithBf() {
        AuthService svc = new AuthService(storage, null, true, false, null);
        String key = svc.generateApiKey();
        assertTrue(key.startsWith("bf_"));
        assertTrue(key.length() > 32);
    }

    // --- validatePassword ---

    @Test
    public void validatePasswordNullReturnsInvalid() {
        AuthService.PasswordValidation result = AuthService.validatePassword(null);
        assertFalse(result.isValid());
        assertNotNull(result.getMessage());
    }

    @Test
    public void validatePasswordEmptyReturnsInvalid() {
        AuthService.PasswordValidation result = AuthService.validatePassword("");
        assertFalse(result.isValid());
    }

    @Test
    public void validatePasswordTooShortReturnsInvalid() {
        AuthService.PasswordValidation result = AuthService.validatePassword("Ab1!");
        assertFalse(result.isValid());
    }

    @Test
    public void validatePasswordMissingUpperReturnsInvalid() {
        AuthService.PasswordValidation result = AuthService.validatePassword("abcdef1!@");
        assertFalse(result.isValid());
    }

    @Test
    public void validatePasswordMissingLowerReturnsInvalid() {
        AuthService.PasswordValidation result = AuthService.validatePassword("ABCDEF1!@");
        assertFalse(result.isValid());
    }

    @Test
    public void validatePasswordMissingDigitReturnsInvalid() {
        AuthService.PasswordValidation result = AuthService.validatePassword("Abcdefg!@");
        assertFalse(result.isValid());
    }

    @Test
    public void validatePasswordMissingSpecialReturnsInvalid() {
        AuthService.PasswordValidation result = AuthService.validatePassword("Abcdefg1");
        assertFalse(result.isValid());
    }

    @Test
    public void validatePasswordValidReturnsSuccess() {
        AuthService.PasswordValidation result = AuthService.validatePassword("Abcdefg1!");
        assertTrue(result.isValid());
    }

    // --- hasPermission ---

    @Test
    public void hasPermissionAdminAlwaysTrue() {
        assertTrue(AuthService.hasPermission(AuthService.Role.ADMIN, AuthService.Resource.RULE, AuthService.Action.DELETE));
        assertTrue(AuthService.hasPermission(AuthService.Role.ADMIN, AuthService.Resource.USER, AuthService.Action.DELETE));
        assertTrue(AuthService.hasPermission(AuthService.Role.ADMIN, AuthService.Resource.RECORDING, AuthService.Action.CREATE));
    }

    @Test
    public void hasPermissionReadAlwaysTrue() {
        assertTrue(AuthService.hasPermission(AuthService.Role.GUEST, AuthService.Resource.RULE, AuthService.Action.READ));
        assertTrue(AuthService.hasPermission(AuthService.Role.TESTER, AuthService.Resource.USER, AuthService.Action.READ));
    }

    @Test
    public void hasPermissionDeveloperCanCreateUpdateDeleteRule() {
        assertTrue(AuthService.hasPermission(AuthService.Role.DEVELOPER, AuthService.Resource.RULE, AuthService.Action.CREATE));
        assertTrue(AuthService.hasPermission(AuthService.Role.DEVELOPER, AuthService.Resource.RULE, AuthService.Action.UPDATE));
        assertTrue(AuthService.hasPermission(AuthService.Role.DEVELOPER, AuthService.Resource.RULE, AuthService.Action.DELETE));
        assertTrue(AuthService.hasPermission(AuthService.Role.DEVELOPER, AuthService.Resource.RULE, AuthService.Action.IMPORT_EXPORT));
    }

    @Test
    public void hasPermissionTesterCannotModifyRule() {
        assertFalse(AuthService.hasPermission(AuthService.Role.TESTER, AuthService.Resource.RULE, AuthService.Action.CREATE));
        assertFalse(AuthService.hasPermission(AuthService.Role.TESTER, AuthService.Resource.RULE, AuthService.Action.DELETE));
    }

    @Test
    public void hasPermissionDeveloperAndTesterCanManageScene() {
        assertTrue(AuthService.hasPermission(AuthService.Role.DEVELOPER, AuthService.Resource.SCENE, AuthService.Action.CREATE));
        assertTrue(AuthService.hasPermission(AuthService.Role.TESTER, AuthService.Resource.SCENE, AuthService.Action.UPDATE));
        assertTrue(AuthService.hasPermission(AuthService.Role.TESTER, AuthService.Resource.SCENE, AuthService.Action.ACTIVATE));
    }

    @Test
    public void hasPermissionGuestCannotManageScene() {
        assertFalse(AuthService.hasPermission(AuthService.Role.GUEST, AuthService.Resource.SCENE, AuthService.Action.CREATE));
        assertFalse(AuthService.hasPermission(AuthService.Role.GUEST, AuthService.Resource.SCENE, AuthService.Action.DELETE));
    }

    @Test
    public void hasPermissionEnvironmentActionsAlwaysFalse() {
        assertFalse(AuthService.hasPermission(AuthService.Role.DEVELOPER, AuthService.Resource.ENVIRONMENT, AuthService.Action.CREATE));
        assertFalse(AuthService.hasPermission(AuthService.Role.TESTER, AuthService.Resource.ENVIRONMENT, AuthService.Action.UPDATE));
        assertFalse(AuthService.hasPermission(AuthService.Role.GUEST, AuthService.Resource.ENVIRONMENT, AuthService.Action.DELETE));
    }

    @Test
    public void hasPermissionUserActionsAlwaysFalse() {
        assertFalse(AuthService.hasPermission(AuthService.Role.DEVELOPER, AuthService.Resource.USER, AuthService.Action.CREATE));
        assertFalse(AuthService.hasPermission(AuthService.Role.TESTER, AuthService.Resource.USER, AuthService.Action.UPDATE));
        assertFalse(AuthService.hasPermission(AuthService.Role.GUEST, AuthService.Resource.USER, AuthService.Action.DELETE));
    }

    @Test
    public void hasPermissionByString() {
        assertTrue(AuthService.hasPermission("admin", "rule", "delete"));
        assertFalse(AuthService.hasPermission("guest", "rule", "create"));
        assertFalse(AuthService.hasPermission("invalid", "rule", "create"));
        assertFalse(AuthService.hasPermission("developer", "invalid", "create"));
    }

    // --- checkPermission ---

    @Test
    public void checkPermissionReturnsFalseForNullArgs() {
        assertFalse(AuthService.checkPermission(null, "GET", "/api/rules"));
        assertFalse(AuthService.checkPermission("admin", null, "/api/rules"));
        assertFalse(AuthService.checkPermission("admin", "GET", null));
    }

    @Test
    public void checkPermissionGetMapsToRead() {
        assertTrue(AuthService.checkPermission("guest", "GET", "/__baafoo__/api/rules"));
        assertTrue(AuthService.checkPermission("guest", "HEAD", "/__baafoo__/api/rules"));
        assertTrue(AuthService.checkPermission("guest", "OPTIONS", "/__baafoo__/api/rules"));
    }

    @Test
    public void checkPermissionPostMapsToCreate() {
        assertTrue(AuthService.checkPermission("developer", "POST", "/__baafoo__/api/rules"));
        assertFalse(AuthService.checkPermission("tester", "POST", "/__baafoo__/api/rules"));
    }

    @Test
    public void checkPermissionPutMapsToUpdate() {
        assertTrue(AuthService.checkPermission("developer", "PUT", "/__baafoo__/api/rules/r1"));
        assertFalse(AuthService.checkPermission("tester", "PUT", "/__baafoo__/api/rules/r1"));
    }

    @Test
    public void checkPermissionDeleteMapsToDelete() {
        assertTrue(AuthService.checkPermission("developer", "DELETE", "/__baafoo__/api/rules/r1"));
        assertFalse(AuthService.checkPermission("guest", "DELETE", "/__baafoo__/api/rules/r1"));
    }

    @Test
    public void checkPermissionUnknownMethodDefaultsToRead() {
        assertTrue(AuthService.checkPermission("guest", "TRACE", "/__baafoo__/api/rules"));
    }

    // --- inferResourceFromPath ---

    @Test
    public void inferResourceFromPathReturnsRuleForNull() {
        assertEquals("rule", AuthService.inferResourceFromPath(null));
    }

    @Test
    public void inferResourceFromPathReturnsRuleForNonApiPath() {
        assertEquals("rule", AuthService.inferResourceFromPath("/some/other/path"));
    }

    @Test
    public void inferResourceFromPathRules() {
        assertEquals("rule", AuthService.inferResourceFromPath("/__baafoo__/api/rules"));
        assertEquals("rule", AuthService.inferResourceFromPath("/__baafoo__/api/rules/r1"));
    }

    @Test
    public void inferResourceFromPathScenes() {
        assertEquals("scene", AuthService.inferResourceFromPath("/__baafoo__/api/scenes"));
        assertEquals("scene", AuthService.inferResourceFromPath("/__baafoo__/api/scenes/s1"));
    }

    @Test
    public void inferResourceFromPathEnvironments() {
        assertEquals("environment", AuthService.inferResourceFromPath("/__baafoo__/api/environments"));
    }

    @Test
    public void inferResourceFromPathMqRelationships() {
        assertEquals("mq-relationship", AuthService.inferResourceFromPath("/__baafoo__/api/mq-relationships"));
    }

    @Test
    public void inferResourceFromPathRecordings() {
        assertEquals("recording", AuthService.inferResourceFromPath("/__baafoo__/api/recordings"));
        assertEquals("recording", AuthService.inferResourceFromPath("/__baafoo__/api/logs"));
    }

    @Test
    public void inferResourceFromPathUsers() {
        assertEquals("user", AuthService.inferResourceFromPath("/__baafoo__/api/users"));
    }

    @Test
    public void inferResourceFromPathAgentsAndStatusAndAuth() {
        assertEquals("rule", AuthService.inferResourceFromPath("/__baafoo__/api/agents"));
        assertEquals("rule", AuthService.inferResourceFromPath("/__baafoo__/api/status"));
        assertEquals("rule", AuthService.inferResourceFromPath("/__baafoo__/api/auth/login"));
        assertEquals("rule", AuthService.inferResourceFromPath("/__baafoo__/api/agent/poll"));
        assertEquals("rule", AuthService.inferResourceFromPath("/__baafoo__/api/rulesets"));
    }

    // --- Role/Resource/Action enum methods ---

    @Test
    public void roleFromValue() {
        assertEquals(AuthService.Role.ADMIN, AuthService.Role.fromValue("admin"));
        assertEquals(AuthService.Role.DEVELOPER, AuthService.Role.fromValue("developer"));
        assertEquals(AuthService.Role.TESTER, AuthService.Role.fromValue("tester"));
        assertEquals(AuthService.Role.GUEST, AuthService.Role.fromValue("guest"));
        assertNull(AuthService.Role.fromValue("unknown"));
    }

    @Test
    public void roleIsValid() {
        assertTrue(AuthService.Role.isValid("admin"));
        assertFalse(AuthService.Role.isValid("superadmin"));
    }

    @Test
    public void resourceFromValue() {
        assertEquals(AuthService.Resource.RULE, AuthService.Resource.fromValue("rule"));
        assertEquals(AuthService.Resource.SCENE, AuthService.Resource.fromValue("scene"));
        assertEquals(AuthService.Resource.USER, AuthService.Resource.fromValue("user"));
        assertNull(AuthService.Resource.fromValue("unknown"));
    }

    @Test
    public void actionFromValue() {
        assertEquals(AuthService.Action.READ, AuthService.Action.fromValue("read"));
        assertEquals(AuthService.Action.IMPORT_EXPORT, AuthService.Action.fromValue("import_export"));
        assertNull(AuthService.Action.fromValue("unknown"));
    }
}
