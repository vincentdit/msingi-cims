package tz.co.msingi.cims.integration.nactvet;

import java.util.Map;

/**
 * NACTVET response codes, as listed in NACTVET's integration guide and used by NOAS, so every NACTVET call explains a
 * refusal in the same words. 200, 201 and 202 are success; the 45x codes are about the token in {@code authorization}.
 */
public final class NactvetCodes {

    private static final Map<Integer, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry(200, "OK"),
            Map.entry(201, "Created"),
            Map.entry(202, "Accepted and queued"),
            Map.entry(400, "Bad request"),
            Map.entry(401, "Authentication failure"),
            Map.entry(403, "Forbidden"),
            Map.entry(404, "Not found"),
            Map.entry(405, "Method not allowed"),
            Map.entry(409, "Conflict"),
            Map.entry(412, "Precondition failed"),
            Map.entry(413, "Request too large"),
            Map.entry(422, "Invalid request"),
            Map.entry(452, "Token not yet valid"),
            Map.entry(453, "Token signature verification failed"),
            Map.entry(454, "Key cannot be empty"),
            Map.entry(456, "Token has an invalid number of segments"),
            Map.entry(458, "Invalid token header encoding"),
            Map.entry(459, "Token algorithm missing"),
            Map.entry(460, "Token algorithm not supported"),
            Map.entry(462, "Token key id missing"),
            Map.entry(463, "Token expired"),
            Map.entry(464, "NACTVET could not sign the data"));

    private NactvetCodes() {
    }

    public static boolean isSuccess(int code) {
        return code == 200 || code == 201 || code == 202;
    }

    /** A refusal about the institution's credentials: 401, 403 or a token problem (452–464). */
    public static boolean isCredentialProblem(int code) {
        return code == 401 || code == 403 || (code >= 452 && code <= 464);
    }

    public static String describe(int code) {
        return DESCRIPTIONS.getOrDefault(code, "Unrecognised NACTVET code");
    }
}
