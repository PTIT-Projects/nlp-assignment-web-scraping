package vn.edu.ptit.duongvct.web_crawling;


import org.jsoup.Jsoup;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;

import java.time.Duration;
import java.util.List;

public class WebCrawlingApplication {

	public static void main(String[] args) {
//		ReviewScraperService service = new ReviewScraperService();
//		service.start();
		String productUrl = "https://www.dienmayxanh.com/bo-lau-nha/bo-lau-nha-hommy-mh-x2-xanh/danh-gia";
		WebDriver driver = new ChromeDriver();
		driver.manage().timeouts().implicitlyWait(Duration.ofMillis(500));
		try {
			driver.get(productUrl);
//			System.out.println(driver.getPageSource());
			//get number of pages
			int numPage = 1;
			List<WebElement> pages = driver.findElements(By.className("pagcomment"));
			for (WebElement x : pages) {
				List<WebElement> numPages = x.findElements(By.tagName("a"));
				numPage = Integer.parseInt(numPages.get(numPages.size() - 2).getText());
			}
			for (int i = 1; i <= numPage; i++) {
				System.out.println("Page: " + i);
				String url = String.format("%s?page=%d", productUrl, i);
				driver.get(url);
				List<WebElement> comments = driver.findElements(By.className("comment-list"));
				if (comments.isEmpty()) {
					continue;
				}
				for (WebElement x : comments) {
					List<WebElement> children = x.findElements(By.xpath("*"));
					for (WebElement y : children) {
						List<WebElement> numUpvotes = y.findElements(By.className("iconcmt-starbuy"));
						List<WebElement> numDownvotes = y.findElements(By.className("iconcmt-unstarbuy"));
						WebElement content = y.findElement(By.className("cmt-txt"));
						String text = content.getText().trim();
						if (!text.isBlank()) {
							text = Jsoup.parse(text).text();
							System.out.println(text);
						}
						System.out.println(numUpvotes.size());
						System.out.println(numDownvotes.size());
					}
//					List<WebElement> cmtContents = x.findElements(By.className("cmt-content"));
//					if (cmtContents.isEmpty()) {
//						continue;
//					}
//					for (WebElement content : cmtContents) {
//						String text = content.getText().trim();
//						if (!text.isBlank()) {
//							text = Jsoup.parse(text).text();
//							System.out.println(text);
//						}
//					}


				}
			}
		} finally {
			driver.quit();
		}
	}

}