package es.in2.desmos.infrastructure.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.bc.BouncyCastleProviderSingleton;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import es.in2.desmos.domain.exceptions.JWTClaimMissingException;
import es.in2.desmos.infrastructure.configs.properties.SecurityProperties;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECPrivateKeySpec;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.security.spec.InvalidKeySpecException;
import java.text.ParseException;
import java.util.Map;

@Slf4j
@Component
public class JwtTokenProvider {

    private final ECKey ecJWK;
    private final ObjectMapper objectMapper;
    private final VerifierService verifierService;

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    // Build a useful Private Key from the hexadecimal private key set in the application.properties
    @Autowired
    public JwtTokenProvider(SecurityProperties securityProperties, ObjectMapper objectMapper, VerifierService verifierService) throws NoSuchAlgorithmException, NoSuchProviderException, InvalidKeySpecException {
        this.objectMapper = objectMapper;
        this.verifierService = verifierService;

        Security.addProvider(BouncyCastleProviderSingleton.getInstance());

        String privateKeyHex = securityProperties.privateKey().replace("0x", "");

        // Convert the private key from hexadecimal to BigInteger
        BigInteger privateKeyInt = new BigInteger(privateKeyHex, 16);

        // Get the curve parameters for secp256r1
        org.bouncycastle.jce.spec.ECParameterSpec bcEcSpec = ECNamedCurveTable.getParameterSpec("secp256r1");
        ECCurve bcCurve = bcEcSpec.getCurve();
        EllipticCurve curve = new EllipticCurve(
                new java.security.spec.ECFieldFp(bcCurve.getField().getCharacteristic()),
                bcCurve.getA().toBigInteger(),
                bcCurve.getB().toBigInteger()
        );
        ECPoint bcG = bcEcSpec.getG();
        java.security.spec.ECPoint g = new java.security.spec.ECPoint(bcG.getAffineXCoord().toBigInteger(), bcG.getAffineYCoord().toBigInteger());

        // Convert the cofactor to int
        int h = bcEcSpec.getH().intValue();
        java.security.spec.ECParameterSpec ecSpec = new java.security.spec.ECParameterSpec(curve, g, bcEcSpec.getN(), h);

        KeyFactory keyFactory = KeyFactory.getInstance("EC", "BC");
        ECPrivateKey privateKey = (ECPrivateKey) keyFactory.generatePrivate(new ECPrivateKeySpec(privateKeyInt, bcEcSpec));

        // Get the public point Q by multiplying the generator G by the private key
        ECPoint q = bcEcSpec.getG().multiply(privateKeyInt);
        q = q.normalize(); // Normalize the public point
        java.security.spec.ECPoint w = new java.security.spec.ECPoint(q.getAffineXCoord().toBigInteger(), q.getAffineYCoord().toBigInteger());

        // Generate the public key from the public point
        ECPublicKey ecPublicKey = (ECPublicKey) keyFactory.generatePublic(new ECPublicKeySpec(w, ecSpec));
        this.ecJWK = new ECKey.Builder(Curve.P_256, ecPublicKey)  // Changed to P-256
                .privateKey(privateKey)
                .keyUse(KeyUse.SIGNATURE)
                .build();
    }

    public String generateTokenWithPayload(String payload) throws JOSEException {

        // Sample JWT claims
        JWTClaimsSet claimsSet = convertPayloadToJWTClaimsSet(payload);
        // Import new Provider to work with ECDSA and Java 17
        ECDSASigner signer = new ECDSASigner(ecJWK);
        signer.getJCAContext().setProvider(BouncyCastleProviderSingleton.getInstance());

        // Create JWT for the ES256 algorithm (P-256)
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256)  // Changed to ES256
                        .type(JOSEObjectType.JWT)
                        .jwk(ecJWK.toPublicJWK())
                        .build(),
                claimsSet);

        // Sign with a private EC key
        jwt.sign(signer);
        return jwt.serialize();
    }

    // Use public keys from Access Node Directory in memory
    public Mono<SignedJWT> validateSignedJwt(String jwtString) {
        try {
            log.debug("Performing M2M validation");
            SignedJWT jwt = getSignedJWT(jwtString);
            return validateM2MJwt(jwtString, jwt);
        } catch (Exception e) {
            log.warn("Error parsing JWT in M2M validation", e);
            return Mono.error(new InvalidKeyException());
        }
    }

    private Mono<SignedJWT> validateM2MJwt(String jwtString, SignedJWT jwt) {
        return verifierService.verifyToken(jwtString)
                .then(Mono.just(jwt));
    }

    public SignedJWT getSignedJWT(String jwtString) throws ParseException {
        return SignedJWT.parse(jwtString);
    }

    public String getClaimFromPayload(Payload payload, String claimName) {
        Object claimValue = payload.toJSONObject().get(claimName);
        if (claimValue == null) {
            throw new JWTClaimMissingException(String.format("The '%s' claim is missing or empty in the JWT payload.", claimName));
        }
        return claimValue.toString();
    }

    private JWTClaimsSet convertPayloadToJWTClaimsSet(String payload) {
        try {
            JsonNode jsonNode = objectMapper.readTree(payload);
            Map<String, Object> claimsMap = objectMapper.convertValue(jsonNode, new TypeReference<>() {
            });
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
            for (Map.Entry<String, Object> entry : claimsMap.entrySet()) {
                builder.claim(entry.getKey(), entry.getValue());
            }
            return builder.build();
        } catch (JsonProcessingException e) {
            log.error("Error while parsing the JWT payload", e);
            throw new JwtException("Error while parsing the JWT payload");
        }
    }
}