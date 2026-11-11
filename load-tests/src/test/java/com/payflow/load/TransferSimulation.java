package com.payflow.load;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * Open-model load: transfers arrive at a fixed rate regardless of how fast the system answers, which is how
 * payment traffic behaves and avoids coordinated omission. Each virtual user sends exactly one transfer.
 * <p>
 * Gatling measures the HTTP side (POST latency, throughput, errors). The end-to-end saga latency is measured
 * inside the services and read from Prometheus by the experiment script.
 * <p>
 * Parameters (system properties): rate [transfers/s], durationSec, rampSec, wallets, initialBalance,
 * walletUrl, transferUrl.
 */
public class TransferSimulation extends Simulation {

    private static final int RATE = Integer.getInteger("rate", 50);
    private static final int DURATION_SEC = Integer.getInteger("durationSec", 60);
    private static final int RAMP_SEC = Integer.getInteger("rampSec", 10);
    private static final int WALLETS = Integer.getInteger("wallets", 100);
    private static final long INITIAL_BALANCE = Long.getLong("initialBalance", 1_000_000L);
    private static final String WALLET_URL = System.getProperty("walletUrl", "http://localhost:8081");
    private static final String TRANSFER_URL = System.getProperty("transferUrl", "http://localhost:8082");

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"");

    /** Filled in before() so that the scenario does not depend on any previous state of the database. */
    private final List<String> walletIds = new CopyOnWriteArrayList<>();

    /**
     * Random pair of distinct wallets and a small amount. Balances are large, so insufficient funds
     * practically never happen and both strategies process the same kind of work.
     */
    private final Iterator<Map<String, Object>> transfers = Stream.generate(() -> {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int source = random.nextInt(walletIds.size());
        int target = (source + 1 + random.nextInt(walletIds.size() - 1)) % walletIds.size();
        return Map.<String, Object>of(
                "source", walletIds.get(source),
                "target", walletIds.get(target),
                "amount", 1 + random.nextInt(10));
    }).iterator();

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(TRANSFER_URL)
            .contentTypeHeader("application/json")
            .acceptHeader("application/json")
            .shareConnections();

    private final ScenarioBuilder createTransfer = scenario("Create transfer")
            .feed(transfers)
            .exec(http("POST /transfers")
                    .post("/api/v1/transfers")
                    .body(StringBody("""
                            {"sourceWalletId":"#{source}","targetWalletId":"#{target}",\
                            "amount":#{amount},"currency":"UAH"}"""))
                    .check(status().is(202)));

    {
        setUp(createTransfer.injectOpen(
                rampUsersPerSec(1).to(RATE).during(RAMP_SEC),
                constantUsersPerSec(RATE).during(DURATION_SEC)))
                .protocols(httpProtocol)
                .assertions(global().failedRequests().percent().lt(1.0));
    }

    @Override
    public void before() {
        HttpClient client = HttpClient.newHttpClient();
        for (int i = 0; i < WALLETS; i++) {
            String body = """
                    {"ownerId":"load-%d","currency":"UAH","initialBalance":%d}""".formatted(i, INITIAL_BALANCE);
            HttpRequest request = HttpRequest.newBuilder(URI.create(WALLET_URL + "/api/v1/wallets"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                Matcher matcher = ID.matcher(response.body());
                if (response.statusCode() != 201 || !matcher.find()) {
                    throw new IllegalStateException("Cannot create wallet: " + response.statusCode() + " " + response.body());
                }
                walletIds.add(matcher.group(1));
            } catch (Exception e) {
                throw new IllegalStateException("Wallet setup failed, is wallet-service up at " + WALLET_URL + "?", e);
            }
        }
        System.out.printf("Created %d wallets; load: %d transfers/s for %ds after a %ds ramp%n",
                walletIds.size(), RATE, DURATION_SEC, RAMP_SEC);
    }
}
