package org.steventc;

import com.fasterxml.jackson.databind.ObjectMapper;
import tech.tablesaw.api.IntColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class USCISScraper {

    final long caseNum = 2390228621L;

    public static void main(String[] args) {
        var scraper = new USCISScraper();

//        var forms = List.of("I-140", "I-765", "I-485", "I-131");
        var forms = List.of("I-485");
        forms.forEach(scraper::execute);
    }


    public record FilingInfo(AtomicInteger count, List<String> ids, List<Long> timeDiff){}
    private void execute(String form){

        var httpClient = HttpClient.newHttpClient();

        var chm = new ConcurrentHashMap<String, FilingInfo>();

        try (var executorService = Executors.newVirtualThreadPerTaskExecutor()) {

            var tasks = new ArrayList<Callable<String>>();
            for (int i = 0; i < 10_000; i++) {
                var caseCode = caseNum - i;
                tasks.add(parseTask(caseCode, httpClient, chm, form));
            }

            var futures = executorService.invokeAll(tasks);
            executorService.shutdown();

            Table caseInfo =
                    Table.create(form)
                            .addColumns(
                                    StringColumn.create("Case Types", chm.keySet().stream()),
                                    IntColumn.create("Case Counts", chm.values().stream().mapToInt(filingInfo -> filingInfo.count.get())));

            System.out.println(caseInfo);

            var total = chm.values().stream().mapToInt(value -> value.count.get()).sum();
            var totalApproved = chm.entrySet().stream().filter(stringFilingInfoEntry -> stringFilingInfoEntry.getKey().toLowerCase().contains("approved")).mapToInt(value -> value.getValue().count.get()).sum();
            System.out.println("Total entries " + total);
            System.out.println("Total approved " + totalApproved);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

    }

    public Callable<String> parseTask(long caseNumber, final HttpClient httpClient, ConcurrentHashMap<String, FilingInfo> chm, String form) {
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
                var filingInfo = chm.computeIfAbsent(filingStatus, s -> new FilingInfo(new AtomicInteger(), new ArrayList<>(), new ArrayList<>()));
                filingInfo.count.incrementAndGet();

                if (filingStatus.toLowerCase().contains("approved")){
                    filingInfo.ids.add("LIN" + caseNumber);
                    filingInfo.timeDiff.add(Math.abs(caseNumber - caseNum));
                }

            }

            return "";

        };
    }
}