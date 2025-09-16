package vn.edu.ptit.duongvct.web_crawling.crawler;

import com.opencsv.CSVWriter;
import org.jsoup.Jsoup;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class PageCrawlerService implements AutoCloseable {
    private final Logger log = LoggerFactory.getLogger(PageCrawlerService.class);
    private final String url;
    private final ConcurrentLinkedQueue<List<String>> csvData;  // Thread-safe queue for collected data
    private static final int DATA_LENGTH = 15000;
    private static final AtomicBoolean isComplete = new AtomicBoolean(false);  // Atomic for thread safety
    private static final AtomicInteger count = new AtomicInteger(0);  // Atomic counter
    private final ExecutorService executor;  // Virtual thread executor

    public PageCrawlerService(String url) {
        this.url = url;
        this.csvData = new ConcurrentLinkedQueue<>();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();  // Virtual thread executor for concurrency
    }

    public List<String> getProductItemsList(String url) {
        // Create a new WebDriver instance for this thread
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        WebDriver driver = new ChromeDriver(options);
        driver.manage().timeouts().implicitlyWait(Duration.ofMillis(500));
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));

        List<String> productLinks = new ArrayList<>();
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
                List<WebElement> productItems = driver.findElements(By.xpath("//*[@id=\"categoryPage\"]/div[1]/ul/*"));
                previousCount = productItems.size();
                log.info("Product items after click: {}", previousCount);
            } catch (Exception e) {
                // end of list, button now shown
                log.info("No more 'Xem thêm' button found or no new items loaded. Stopping.");
                break;
            }
        }
        // Extract links from the final productItems before quitting
        List<WebElement> finalProductItems = driver.findElements(By.xpath("//*[@id=\"categoryPage\"]/div[1]/ul/*"));
        for (WebElement item : finalProductItems) {
            try {
                WebElement aTag = item.findElement(By.tagName("a"));
                String link = aTag.getAttribute("href");
                if (link != null) {
                    productLinks.add(link);
                }
            } catch (Exception e) {
                log.warn("Failed to extract link from item: {}", e.getMessage());
            }
        }
        log.info("Total product links extracted: {}", productLinks.size());
        driver.quit();  // Close driver after use
        return productLinks;
    }

    private List<String> getAllProductPageLink() {
        // Create a new WebDriver instance for this thread
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        WebDriver driver = new ChromeDriver(options);
        driver.manage().timeouts().implicitlyWait(Duration.ofMillis(500));

        driver.get(url);
        List<String> result = new ArrayList<>();
        List<WebElement> elements = driver.findElements(By.xpath("/html/body/header/div[1]/div[2]/div/ul/*"));
        elements = elements.subList(2, elements.size() - 1);

        for (WebElement element : elements) {
            List<WebElement> menuItems = element.findElements(By.className("menuitem"));
            for (WebElement menu : menuItems) {
                List<WebElement> aTags = menu.findElements(By.tagName("a"));
                for (WebElement item : aTags) {
                    String href = item.getAttribute("href");
                    if (href != null && (href.startsWith("http://") || href.startsWith("https://"))) {
                        result.add(href);
                    }
                }
            }
        }
        driver.quit();  // Close driver after use
        return result;
    }

    public void startCrawlData() {
        csvData.clear();
        count.set(0);
        isComplete.set(false);

        // Add header
        List<String> header = new ArrayList<>();
        header.add("data");
        header.add("label");
        csvData.add(header);

        List<String> productPageLinks = getAllProductPageLink();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // Limit concurrency to 10 threads to avoid overwhelming the site
        Semaphore semaphore = new Semaphore(10);

        for (String link : productPageLinks) {
            if (isComplete.get()) break;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    semaphore.acquire();  // Acquire permit
                    List<String> productLinks = getProductItemsList(link);  // Now returns List<String>
                    for (String productLink : productLinks) {
                        if (isComplete.get()) break;
                        scrapeAllReviews(productLink);  // Scrape reviews for this product
                    }
                } catch (Exception e) {
                    log.error("Error processing link {}: {}", link, e.getMessage());
                } finally {
                    semaphore.release();  // Release permit
                }
            }, executor);
            futures.add(future);
        }

        // Wait for all tasks to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Export collected data
        exportToCSV(new ArrayList<>(csvData), String.format("reviews%d.csv", DATA_LENGTH));
    }

    private void scrapeAllReviews(String url) {
        // Create a new WebDriver instance for this thread
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        WebDriver driver = new ChromeDriver(options);
        driver.manage().timeouts().implicitlyWait(Duration.ofMillis(500));
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));

        try {
            url += "/danh-gia";
            driver.get(url);
            int numPage = getNumberOfReviewPages(driver);
            for (int i = 1; i <= numPage; i++) {
                if (isComplete.get()) break;
                log.info("Page: {}", i);
                String newUrl = String.format("%s?page=%d", url, i);
                driver.get(newUrl);
                List<WebElement> comments = driver.findElements(By.className("comment-list"));
                if (comments.isEmpty()) continue;
                for (WebElement commentItem : comments) {
                    List<WebElement> commentDetails = commentItem.findElements(By.xpath("*"));
                    for (WebElement comment : commentDetails) {
                        WebElement content = comment.findElement(By.className("cmt-txt"));
                        String text = content.getText().trim();
                        if (!text.isBlank()) {
                            text = Jsoup.parse(text).text();
                            boolean isContent = isContentReview(comment);
                            int label = isContent ? 1 : 0;
                            List<String> row = new ArrayList<>();
                            row.add(text);
                            row.add(String.valueOf(label));
                            int currentCount = count.incrementAndGet();
                            if (currentCount > DATA_LENGTH) {
                                isComplete.set(true);
                                return;
                            }
                            csvData.add(row);
                            log.info("Count: {}", currentCount);
                            log.info("Comment: {} | Label: {}", text, label);
                        }
                    }
                }
            }
        } finally {
            driver.quit();  // Always close the driver
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
            WebElement pages = webDriver.findElement(By.className("pagcomment"));
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
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    }
}