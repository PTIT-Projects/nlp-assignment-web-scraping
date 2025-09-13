package vn.edu.ptit.duongvct.web_crawling;


import vn.edu.ptit.duongvct.web_crawling.crawler.PageCrawlerService;

public class WebCrawlingApplication {

	public static void main(String[] args) throws Exception {
		String url = "https://www.dienmayxanh.com/";
		try (PageCrawlerService service = new PageCrawlerService(url)) {
			service.startCrawlData();
		}
	}
}