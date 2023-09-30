package org.steventc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.j2objc.annotations.ObjectiveCName;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.steventc.db.generated.tables.Case;
import org.steventc.db.generated.tables.CaseHistory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import static org.jooq.impl.DSL.*;

public class DBPopulator {

    final long caseNum = 2390228621L;

    final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) {
        var scraper = new DBPopulator();

        var forms = Set.of("I-140", "I-765", "I-485", "I-131");


        var batches = 5;
        var batchSize = 5000;

        for (int i = 1; i < batches + 1; i++) {
            scraper.execute(forms, i, batchSize);
        }

    }


    public record FilingInfo(String caseId, String formType, long timeDiff, LocalDate date){}

    Pattern datePattern = Pattern.compile("(January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{1,2},\\s+\\d{4}");

    private void execute(Set<String> form, int batchNumber, int batchSize){

        var httpClient = HttpClient.newHttpClient();

        var chm = new ConcurrentHashMap<String, TreeSet<FilingInfo>>();

        try (var executorService = Executors.newVirtualThreadPerTaskExecutor()) {

            var tasks = new ArrayList<Callable<String>>();
            for (int i = 0; i < batchSize; i++) {
                var caseCodePrev = caseNum - ((long) i * batchNumber);
                var caseCodeAfter = caseNum + ((long) i * batchNumber);
                tasks.add(parseTask(caseCodePrev, httpClient, chm, form));
                tasks.add(parseTask(caseCodeAfter, httpClient, chm, form));
            }

            var futures = executorService.invokeAll(tasks, 3, TimeUnit.MINUTES);
            executorService.shutdown();


        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        httpClient.close();

        Connection connection;
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:/var/tmp/steventc/uscis_visa.db");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        var dslContext = using(connection, SQLDialect.SQLITE);

        persistToDb(chm, connection, dslContext);



    }

    private void persistToDb(ConcurrentHashMap<String, TreeSet<FilingInfo>> chm, Connection connection, DSLContext dslContext) {

        try (var executorService = Executors.newVirtualThreadPerTaskExecutor()) {

            for (var entry : chm.entrySet()) {

                for (FilingInfo filingInfo : entry.getValue()) {

                    executorService.submit(() -> {
                        dslContext.insertInto(Case.CASE)
                                .set(Case.CASE.CASE_NUMBER, filingInfo.caseId)
                                .set(Case.CASE.CASE_TYPE, filingInfo.formType)
                                .set(Case.CASE.LATEST_UPDATE, entry.getKey())
                                .set(Case.CASE.UPDATE_DATE, filingInfo.date)
                                .execute();


                        dslContext.insertInto(CaseHistory.CASE_HISTORY)
                                .set(CaseHistory.CASE_HISTORY.CASE_NUMBER, filingInfo.caseId)
                                .set(CaseHistory.CASE_HISTORY.STATUS, entry.getKey())
                                .set(CaseHistory.CASE_HISTORY.UPDATE_DATE, filingInfo.date)
                                .execute();

                    });

                }

            }
        }

        try {
            connection.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }


    }

    public Callable<String> parseTask(long caseNumber, final HttpClient httpClient, ConcurrentHashMap<String, TreeSet<FilingInfo>> chm, Set<String> form) {
        return () -> {

            var caseUrl = "https://egov.uscis.gov/csol-api/case-statuses/LIN" + caseNumber;

            var request = HttpRequest.newBuilder()
                    .header("Accept", "*/*")
                    .header("content-type", "application/json")
                    .header("Sec-Fetch-Dest", "empty")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36")
                    .header("sec-ch-ua", "\"Chromium\";v=\"116\", \"Not)A;Brand\";v=\"24\", \"Google Chrome\";v=\"116\"")
                    .header("sec-ch-ua-platform", "macOS")
                    .uri(new URI("https://egov.uscis.gov/csol-api/ui-auth"))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            var jsonNode = mapper.readTree(response.body());
            var accessToken = jsonNode.at("/JwtResponse/accessToken").asText();

            var caseReq = HttpRequest.newBuilder()
                    .header("Accept", "*/*")
                    .header("Authorization", "Bearer " + accessToken)
                    .header("content-type", "application/json")
                    .header("Sec-Fetch-Dest", "empty")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36")
                    .header("sec-ch-ua", "\"Chromium\";v=\"116\", \"Not)A;Brand\";v=\"24\", \"Google Chrome\";v=\"116\"")
                    .header("sec-ch-ua-platform", "macOS")
                    .uri(new URI(caseUrl))
                    .build();


            var caseResponse = httpClient.send(caseReq, HttpResponse.BodyHandlers.ofString());

            var json = mapper.readTree(caseResponse.body());
            var formInfo = json.get("CaseStatusResponse").get("detailsEng");
            var formType = formInfo.get("formNum").asText();
            if (form.contains(formType)){
                var filingStatus = formInfo.get("actionCodeText").textValue();
                var matcher = datePattern.matcher(formInfo.get("actionCodeDesc").textValue());
                matcher.find();
                var fileInf = new FilingInfo("LIN" + caseNumber, formType, Math.abs(caseNumber - caseNum), convertToDate(matcher.group()));
                chm.computeIfAbsent(filingStatus, s -> new TreeSet<>(Comparator.comparing(FilingInfo::caseId))).add(fileInf);
            }

            return "";

        };


    }

    public static LocalDate convertToDate(String dateString) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMMM dd, yyyy");
        return LocalDate.parse(dateString, formatter);
    }
}