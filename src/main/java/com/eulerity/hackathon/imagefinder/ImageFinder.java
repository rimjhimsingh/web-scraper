package com.eulerity.hackathon.imagefinder;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

/**
 * Servlet that handles the finding and collection of images from specified URLs.
 */
@WebServlet(name = "ImageFinder", urlPatterns = { "/main" })
public class ImageFinder extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Gson GSON = new GsonBuilder().create();
    private static final int MAX_DEPTH = 2;
    private static final long RATE_LIMIT_MS = 1000; // 1 second between requests

    private ConcurrentHashMap<String, Boolean> visited = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, Boolean> imageSet = new ConcurrentHashMap<>();
    public static final ConcurrentLinkedQueue<String> testImages = new ConcurrentLinkedQueue<>();

    /**
     * Processes POST requests to start the image finding process on a given URL.
     */
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
        visited.put(url, true);
        crawl(url, 1, startDomain);
        resp.getWriter().print(GSON.toJson(new ArrayList<>(testImages)));
    }

    /**
     * Sets up the response headers for CORS and content type.
     */
    private void setResponseHeaders(HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
        resp.setContentType("application/json");
    }

    /**
     * Validates the request ensuring a URL is provided and it is not empty.
     */
    private String validateRequest(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String url = req.getParameter("url");
        if (url == null || url.isEmpty()) {
            resp.getWriter().print(GSON.toJson(new ArrayList<>()));
            return null;
        }
        return url;
    }

    /**
     * Initiates the crawling process for the provided URL up to a specified depth.
     */
    private void crawl(String url, int depth, String startDomain) throws IOException {
        if (depth > MAX_DEPTH) return;
        respectRateLimit();  // Enforce rate limiting
        Document doc = fetchDocument(url);
        if (doc != null) {
            processImages(doc, startDomain);
            followLinks(doc, depth, startDomain);
        }
    }

    /**
     * Processes images found in the document, adding unique images from the same domain to a queue.
     */
    private void processImages(Document doc, String startDomain) {
        Elements imageElements = doc.select("img[src]");
        for (Element image : imageElements) {
            String imageUrl = image.attr("abs:src");
            try {
                String imageDomain = getDomainName(imageUrl);
                if (imageDomain.equals(startDomain) && imageSet.putIfAbsent(imageUrl, true) == null) {
                    if (mightBeLogo(image)) {
                        System.out.println("Found a logo: " + imageUrl);  // Optionally handle logos differently
                    } else {
                        testImages.offer(imageUrl);
                    }
                }
            } catch (URISyntaxException e) {
                // System.err.println("Error parsing URL: " + imageUrl);
            }
        }
    }

    /**
     * Follows hyperlinks from the current document, queuing further crawling tasks.
     */
    private void followLinks(Document doc, int depth, String startDomain) throws IOException {
        if (depth >= MAX_DEPTH) return;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        Elements links = doc.select("a[href]");
        for (Element link : links) {
            String nextLink = link.absUrl("href");
            if (!isValidLink(nextLink, startDomain)) continue;
            executor.submit(() -> {
                try {
                    if (visited.putIfAbsent(nextLink, true) == null) {
                        crawl(nextLink, depth + 1, startDomain);
                    }
                } catch (IOException e) {
                    System.err.println("Error processing URL: " + nextLink);
                }
            });
        }
        executor.shutdown();
        try {
            executor.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            System.err.println("Thread interrupted: " + e.getMessage());
        }
    }
     /**
     * Checks if a given URL is valid for following based on protocol and domain restrictions.
     * 
     * @param url The URL to check.
     * @param startDomain The domain from which the link was extracted to ensure it matches the originating domain.
     * @return true if the link is valid, false otherwise.
     */
    private boolean isValidLink(String url, String startDomain) {
        return (url.startsWith("http://") || url.startsWith("https://")) && !url.contains("x.com:") && !url.startsWith("mailto:");
    }

    /**
     * Fetches and parses a document from the provided URL.
     */
    private Document fetchDocument(String url) {
        try {
            return Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36")
                    .get();
        } catch (IOException e) {
            System.err.println("HTTP error fetching URL: " + e);
            return null;
        }
    }

    /**
     * Extracts the domain name from a URL to ensure image gathering from the same domain.
     */
    private String getDomainName(String url) throws URISyntaxException {
        URI uri = new URI(url);
        String domain = uri.getHost();
        if (domain == null) {
            throw new URISyntaxException(url, "Host is null");
        }
        return domain.startsWith("www.") ? domain.substring(4) : domain;
    }

    /**
     * Enforces a rate limit by pausing the thread to prevent request flooding.
     */
    private void respectRateLimit() {
        try {
            Thread.sleep(RATE_LIMIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Determines if an image might be a logo based on various attributes.
     */
    private boolean mightBeLogo(Element image) {
        String src = image.attr("src");
        boolean isSmall = image.attr("width").equals("100") || image.attr("height").equals("100"); // Example size condition
        boolean inHeader = image.parents().stream().anyMatch(parent -> parent.tagName().equals("header"));
        boolean fileNameHint = src.toLowerCase().contains("logo");
        return isSmall || inHeader || fileNameHint;
    }
}
