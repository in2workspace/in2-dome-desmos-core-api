package es.in2.desmos.objectmothers;

import com.fasterxml.jackson.core.JsonProcessingException;
import es.in2.desmos.domain.models.DataNegotiationResult;
import es.in2.desmos.domain.models.MVEntity4DataNegotiation;
import org.json.JSONException;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public final class DataNegotiationResultMother {
    private DataNegotiationResultMother() {
    }

    public static DataNegotiationResult sample() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String issuer = "http://example.org";

        List<MVEntity4DataNegotiation> newEntitiesToSync = List.of(
                MVEntity4DataNegotiationMother.sample1(),
                MVEntity4DataNegotiationMother.sample2());

        List<MVEntity4DataNegotiation> existingEntitiesToSync =
                List.of(MVEntity4DataNegotiationMother.sample3(),
                        MVEntity4DataNegotiationMother.sample4());

        return new DataNegotiationResult(issuer, newEntitiesToSync, existingEntitiesToSync);
    }

    public static DataNegotiationResult sampleBadHash2() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String issuer = "http://example.org";

        List<MVEntity4DataNegotiation> newEntitiesToSync = List.of(
                MVEntity4DataNegotiationMother.sample1(),
                new MVEntity4DataNegotiation(
                        MVEntity4DataNegotiationMother.sample2().id(),
                        MVEntity4DataNegotiationMother.sample2().type(),
                        MVEntity4DataNegotiationMother.sample2().version(),
                        MVEntity4DataNegotiationMother.sample2().lastUpdate(),
                        MVEntity4DataNegotiationMother.sample2().lifecycleStatus(),
                        MVEntity4DataNegotiationMother.sample2().startDateTime(),
                        MVEntity4DataNegotiationMother.sample2().endDateTime(),
                        "fdafdsa",
                        MVEntity4DataNegotiationMother.sample2().hashlink()));

        List<MVEntity4DataNegotiation> existingEntitiesToSync =
                List.of(MVEntity4DataNegotiationMother.sample3(),
                        MVEntity4DataNegotiationMother.sample4());

        return new DataNegotiationResult(issuer, newEntitiesToSync, existingEntitiesToSync);
    }

    public static DataNegotiationResult badHash() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String issuer = "http://example.org";
        List<MVEntity4DataNegotiation> newEntitiesToSync = MVEntity4DataNegotiationMother.listbadHash1And2();
        List<MVEntity4DataNegotiation> existingEntitiesToSync = MVEntity4DataNegotiationMother.list3And4().collectList().block(); //TODO: revisar esto
        return new DataNegotiationResult(issuer, newEntitiesToSync, existingEntitiesToSync);
    }

    public static List<DataNegotiationResult> listNewToSync2and4(String issuerExistingToSync2, String issuerNewToSync4) throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        List<DataNegotiationResult> dataNegotiationResults = new ArrayList<>();
        dataNegotiationResults.add(newToScorpioSync2(issuerExistingToSync2));
        dataNegotiationResults.add(newToScorpioSync4(issuerNewToSync4));
        return dataNegotiationResults;
    }

    public static DataNegotiationResult newToSync4AndExistingToSync2() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        List<MVEntity4DataNegotiation> newEntitiesToSync = List.of(MVEntity4DataNegotiationMother.sample4());
        List<MVEntity4DataNegotiation> existingEntitiesToSync = List.of(MVEntity4DataNegotiationMother.sample2());
        return new DataNegotiationResult("http://example.org", newEntitiesToSync, existingEntitiesToSync);
    }

    public static DataNegotiationResult empty(){
        List<MVEntity4DataNegotiation> newEntitiesToSync = new ArrayList<>();
        List<MVEntity4DataNegotiation> existingEntitiesToSync = new ArrayList<>();
        return new DataNegotiationResult("http://example.org", newEntitiesToSync, existingEntitiesToSync);
    }

    public static DataNegotiationResult newToScorpioSync2(String issuer) throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        List<MVEntity4DataNegotiation> newEntitiesToSync = List.of(MVEntity4DataNegotiationMother.sample2());
        List<MVEntity4DataNegotiation> existingEntitiesToSync = new ArrayList<>();
        return new DataNegotiationResult(issuer, newEntitiesToSync, existingEntitiesToSync);
    }

    private static DataNegotiationResult newToScorpioSync4(String issuer) throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        List<MVEntity4DataNegotiation> newEntitiesToSync = List.of(MVEntity4DataNegotiationMother.sample4());
        List<MVEntity4DataNegotiation> existingEntitiesToSync = new ArrayList<>();
        return new DataNegotiationResult(issuer, newEntitiesToSync, existingEntitiesToSync);
    }
}
