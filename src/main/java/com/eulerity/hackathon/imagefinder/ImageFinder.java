package com.eulerity.hackathon.imagefinder;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

@WebServlet(name = "ImageFinder", urlPatterns = { "/main" })
public class ImageFinder extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Gson GSON = new GsonBuilder().create();
    private static final int MAX_DEPTH = 2;
    HashSet<String> visited = new HashSet<>();
    HashSet<String> imageSet = new HashSet<>();
    public static final ArrayList<String> testImages = new ArrayList<>();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        setResponseHeaders(resp);
        String url = validateRequest(req, resp);
        testImages.clear();
        imageSet.clear();
        visited.clear();
        if (url == null) return;
        String startDomain;
        try {
            startDomain = getDomainName(url);
			
        } catch (URISyntaxException e) {
            resp.getWriter().print(GSON.toJson("Invalid URL"));
            return;
        }
        visited.add(url);
        crawl(url, 1, visited, testImages, imageSet, startDomain);
        resp.getWriter().print(GSON.toJson(testImages));
    }

    private void setResponseHeaders(HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
        resp.setContentType("application/json");
    }

    private String validateRequest(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String url = req.getParameter("url");
        String path = req.getServletPath();
        if (url == null || url.isEmpty()) {
            resp.getWriter().print(GSON.toJson(new ArrayList<String>()));
            return null;
        }
        System.out.println("Got request at:" + path + " with URL parameter:" + url);
        return url;
    }

    private void crawl(String url, int depth, HashSet<String> visited, ArrayList<String> testImages, HashSet<String> imageSet, String startDomain) throws IOException {
        if (depth > MAX_DEPTH) return;

        Document doc = fetchDocument(url);
        if (doc != null) {
            processImages(doc, testImages, imageSet, startDomain);
            followLinks(doc, depth, visited, testImages, imageSet, startDomain);
        }
    }

    private Document fetchDocument(String url) {
        try {
            return Jsoup.connect(url)
                    .userAgent(
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36")
                    .get();

        } catch (org.jsoup.HttpStatusException e) {
            System.err.println("HTTP error fetching URL. Status=" + e.getStatusCode() + " for URL: " + url);
            return null;
        } catch (IOException e) {
            System.err.println("General I/O exception occurred for URL: " + url);
            e.printStackTrace();
            return null;
        }
    }

    private void processImages(Document doc, ArrayList<String> testImages, HashSet<String> imageSet, String startDomain) {
		Elements imageElements = doc.select("img[src]");
		for (Element image : imageElements) {
			String imageUrl = image.attr("abs:src");
			try {
				String imageDomain = getDomainName(imageUrl);
				if (imageDomain.equals(startDomain) && imageSet.add(imageUrl)) {
					testImages.add(imageUrl);
					System.out.println(imageUrl);
				}
			} catch (URISyntaxException e) {
				System.err.println("Error parsing URL: " + imageUrl);
			}
		}
	}
	

	private void followLinks(Document doc, int depth, HashSet<String> visited, ArrayList<String> testImages, HashSet<String> imageSet, String startDomain) {
		Elements links = doc.select("a[href]");
        int count=0;
		for (Element link : links) {
            if (count >= 5) { // Limit to the first 10 links
                break; // Stop processing more links if 10 have been processed
            }
			String nextLink = link.absUrl("href");
			if (nextLink.startsWith("mailto:")) {
				continue; // Skip mailto links
			}
			if (!nextLink.startsWith("http://") && !nextLink.startsWith("https://")) {
				continue;
			}
			try {
				String nextLinkDomain = getDomainName(nextLink);
				if (!visited.contains(nextLink) && nextLinkDomain.equals(startDomain)) {
					crawl(nextLink, depth + 1, visited, testImages, imageSet, startDomain);
                    count++;
				}
			} catch (URISyntaxException e) {
				System.err.println("Error parsing URL: " + nextLink);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

    private String getDomainName(String url) throws URISyntaxException {
		if (url == null) {
			throw new URISyntaxException(url, "URL is null");
		}
		URI uri = new URI(url);
		String domain = uri.getHost();
		if (domain == null) {
			throw new URISyntaxException(url, "Host is null");
		}
		return domain.startsWith("www.") ? domain.substring(4) : domain;
	}
}