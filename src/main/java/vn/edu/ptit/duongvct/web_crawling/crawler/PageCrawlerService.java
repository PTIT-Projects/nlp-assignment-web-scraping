package vn.edu.ptit.duongvct.web_crawling.crawler;

import com.opencsv.CSVWriter;
import org.jsoup.Jsoup;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class PageCrawlerService implements AutoCloseable{
    private final Logger log = LoggerFactory.getLogger(PageCrawlerService.class);
    private final WebDriver driver;
    private final String url;
    private final WebDriverWait wait;
    private  List<List<String>> csvData;
    private static final int DATA_LENGTH = 500;
    private static boolean isComplete = false;
    private static int count = 0;
    public PageCrawlerService(String url) {
        this.driver = new ChromeDriver();
        driver.manage().timeouts().implicitlyWait(Duration.ofMillis(500));
        this.url = url;
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        csvData = new ArrayList<>();
    }

    public List<WebElement> getProductItemsList(String url) {
        List<WebElement> productItems = new ArrayList<>();
        driver.get(url);
        int previousCount = 0;
        // Loop: while the "see more" button is present, click it and wait for item count to increase
        while (true) {
            try {
                WebElement seeMoreButton = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//*[@id=\"categoryPage\"]/div[1]/div[2]/a")));
                log.info("Clicking 'Xem thêm' button: {}", seeMoreButton.getText());
                seeMoreButton.click();
                // Wait until the number of product items increases
                int finalPreviousCount = previousCount;
                wait.until(d -> {
                    List<WebElement> items = d.findElements(By.xpath("//*[@id=\"categoryPage\"]/div[1]/ul/*"));
                    return items.size() > finalPreviousCount;
                });
                productItems = driver.findElements(By.xpath("//*[@id=\"categoryPage\"]/div[1]/ul/*"));
                previousCount = productItems.size();
                log.info("Product items after click: {}",previousCount);
            } catch (Exception e) {
                // end of list, button now shown
                log.info("No more 'Xem thêm' button found or no new items loaded. Stopping.");
                break;
            }
        }
        log.info(String.valueOf(productItems.size()));
        return productItems;
    }
    private List<String> getAllProductPageLink() {
        driver.get(url);
        List<String> result = new ArrayList<>();
        List<WebElement> elements = driver.findElements(By.xpath("/html/body/header/div[1]/div[2]/div/ul/*"));
        elements = elements.subList(2, elements.size() - 1);

        for (WebElement element : elements) {
            List<WebElement> menuItems = element.findElements(By.className("menuitem"));
            for (WebElement menu : menuItems) {
                List<WebElement> aTags = menu.findElements(By.tagName("a"));
                for(WebElement item : aTags) {
                    String href = item.getAttribute("href");
                    if (href != null && (href.startsWith("http://") || href.startsWith("https://"))) {
                        result.add(href);
                    }
                }
            }
        }
        return result;
    }
    private List<String> getProductLink(List<WebElement> productItems) {
        List<String> result = new ArrayList<>();
        productItems.forEach(item -> {
            WebElement aTag = item.findElement(By.tagName("a"));
            String link = aTag.getAttribute("href");
//            log.info("Link product: {}", link);
            result.add(link);
        });
        return result;
    }
    public void startCrawlData() {
        csvData.clear();
        count = 0;
        List<String> header = new ArrayList<>();
        header.add("data");
        header.add("label");
        csvData.add(header);
        List<String> productPageLinks = getAllProductPageLink();
        for (String link : productPageLinks) {
            List<WebElement> productItems =  this.getProductItemsList(link);
            List<String> productLinks = getProductLink(productItems);
            for (String productLink : productLinks) {
                scrapeAllReviews(productLink);
                if (isComplete) {
                    break;
                }
            }
            if (isComplete) {
                break;
            }
        }
        exportToCSV(csvData, "reviews.csv");
    }
    private void scrapeAllReviews(String url) {
        url += "/danh-gia";
        driver.get(url);
        //get number of pages
        int numPage = getNumberOfReviewPages(driver);
        for (int i = 1; i <= numPage; i++) {
            log.info("Page: {}", i);
            String newUrl = String.format("%s?page=%d", url, i);
            driver.get(newUrl);
            List<WebElement> comments = driver.findElements(By.className("comment-list"));
            if (comments.isEmpty()) {
                continue;
            }
            for (WebElement commentItem : comments) {
                List<WebElement> commentDetails = commentItem.findElements(By.xpath("*"));
                for (WebElement comment : commentDetails) {
                    WebElement content = comment.findElement(By.className("cmt-txt"));
                    String text = content.getText().trim();
                    if (!text.isBlank()) {
                        text = Jsoup.parse(text).text();
//                        log.info(text);
                        boolean isContent = isContentReview(comment);
                        int label = isContent ? 1 : 0;
                        List<String> row = new ArrayList<>();
                        row.add(text);
                        row.add(String.valueOf(label));
                        count++;
                        if (count > DATA_LENGTH) {
                            isComplete = true;
                            return;
                        }
                        csvData.add(row);
                        log.info("Count: {}", count);
                        log.info("Comment: {} | Label: {}", text, label);
//                        break;
                    }
                }
            }
        }
    }
    private boolean isContentReview(WebElement review) {
        List<WebElement> numUpvotes = review.findElements(By.className("iconcmt-starbuy"));
        log.info("Number of stars: {}", numUpvotes.size());
        if (numUpvotes.size() >= 3) {
            return true;
        }
        return false;
    }
    private int getNumberOfReviewPages(WebDriver webDriver) {
        int numPage = 1;
        try {
            WebElement pages = driver.findElement(By.className("pagcomment"));
            List<WebElement> numPages = pages.findElements(By.tagName("a"));
            if (!numPages.isEmpty()) {
                numPage = Integer.parseInt(numPages.get(numPages.size() - 2).getText());
            }
        } catch (NoSuchElementException e) {
            log.info("No pagination found, assuming 1 page");
        }
        log.info("Number of pages: {}", numPage);
        return numPage;
    }
    private void exportToCSV(List<List<String>> data, String fileName) {
        try (OutputStreamWriter fileWriter = new OutputStreamWriter(new FileOutputStream(fileName), StandardCharsets.UTF_8);
             CSVWriter writer = new CSVWriter(fileWriter)) {
            for (List<String> row : data) {
                writer.writeNext(row.toArray(new String[0]));
            }
            log.info("CSV file exported successfully: {}", fileName);
        } catch (IOException e) {
            log.error("Error writing CSV file: {}", e.getMessage());
        }
    }

    @Override
    public void close() throws Exception {
        driver.quit();
    }
}
