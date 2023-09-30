package org.steventc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import tech.tablesaw.api.DateColumn;
import tech.tablesaw.api.IntColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

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
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class USCISScraper2 {

    final long caseNum = 2390228621L;

    public static void main(String[] args) {
        var scraper = new USCISScraper2();

//        var forms = List.of("I-140", "I-765", "I-485", "I-131");
        var forms = List.of("I-131");
        forms.forEach(scraper::execute);
    }


    public record FilingInfo(String caseId, long timeDiff, LocalDate date){}

    Pattern datePattern = Pattern.compile("(January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{1,2},\\s+\\d{4}");


    private void execute(String form){

        var httpClient = HttpClient.newHttpClient();

        var chm = new ConcurrentHashMap<String, TreeSet<FilingInfo>>();

        try (var executorService = Executors.newVirtualThreadPerTaskExecutor()) {

            var tasks = new ArrayList<Callable<String>>();
            for (int i = 0; i < 2500; i++) {
                var caseCode = caseNum - i;
                tasks.add(parseTask(caseCode, httpClient, chm, form));
                tasks.add(parseTask(caseNum + i, httpClient, chm, form));
            }

            var futures = executorService.invokeAll(tasks, 3, TimeUnit.MINUTES);
            executorService.shutdown();


            var firstDates = new ArrayList<LocalDate>();
            var earliestCastCode = new ArrayList<String>();

            var lastDates = new ArrayList<LocalDate>();
            var latestCaseCode = new ArrayList<String>();

            for (var value : chm.values()) {
                if (value.isEmpty()){
                    earliestCastCode.add("NA");
                    latestCaseCode.add("NA");
                    continue;
                }
                earliestCastCode.add(value.stream().min(Comparator.comparing(FilingInfo::date)).get().caseId);
                latestCaseCode.add(value.stream().max(Comparator.comparing(FilingInfo::date)).get().caseId);
            }

            chm.values().stream().map(value -> value.stream().map(filingInfo -> filingInfo.date).collect(Collectors.toCollection(TreeSet::new))).forEach(collect -> {
                if (collect.isEmpty()) {
                    collect.add(LocalDate.MAX);
                }
                firstDates.add(collect.getFirst());
                lastDates.add(collect.getLast());
            });


            persistToDb(chm);



            Table caseInfo =
                    Table.create(form)
                            .addColumns(
                                    StringColumn.create(form + " Case Types", chm.keySet().stream()),
                                    IntColumn.create("Case Counts", chm.values().stream().mapToInt(TreeSet::size)),
                                    DateColumn.create("Earliest Date", firstDates.stream()),
                                    StringColumn.create("Earliest Case Code", earliestCastCode.stream()),
                                    DateColumn.create("Latest Date", lastDates.stream()),
                                    StringColumn.create("Latest Case Code", latestCaseCode.stream())
                            );

            System.out.println(caseInfo);

            var total = chm.values().stream().mapToInt(TreeSet::size).sum();
            var totalApproved = chm.entrySet().stream().filter(stringFilingInfoEntry -> stringFilingInfoEntry.getKey().toLowerCase().contains("approved")).mapToInt(value -> value.getValue().size()).sum();
            System.out.println("Total entries " + total);
            System.out.println("Total approved " + totalApproved);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

    }

    private void persistToDb(ConcurrentHashMap<String, TreeSet<FilingInfo>> chm) {

        Connection connection = null;
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:/var/tmp/steventc/uscis_visa.db");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        var dslContext = DSL.using(connection, SQLDialect.SQLITE);

        for (var entry : chm.entrySet()) {

//            dslContext.insertInto(DSL.table("case"))
//                    .set(DSL.field("case_number"), );
        }

    }

    public Callable<String> parseTask(long caseNumber, final HttpClient httpClient, ConcurrentHashMap<String, TreeSet<FilingInfo>> chm, String form) {
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
            var mapper = new ObjectMapper();
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
            if (form.equals(formInfo.get("formNum").asText())){

                var filingStatus = formInfo.get("actionCodeText").textValue();
                var matcher = datePattern.matcher(formInfo.get("actionCodeDesc").textValue());
                matcher.find();
                var fileInf = new FilingInfo("LIN" + caseNumber, Math.abs(caseNumber - caseNum), convertToDate(matcher.group()));
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