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
 * Servlet implementation class ImageFinder that crawls given URL for images matching the same domain.
 * It implements a basic web crawler that searches for images recursively to a specified depth.
 */
@WebServlet(name = "ImageFinder", urlPatterns = { "/main" })
public class ImageFinder extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Gson GSON = new GsonBuilder().create();
    private static final int MAX_DEPTH = 2;

    private ConcurrentHashMap<String, Boolean> visited = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, Boolean> imageSet = new ConcurrentHashMap<>();
    public static final ConcurrentLinkedQueue<String> testImages = new ConcurrentLinkedQueue<>();

    /**
     * Handles the POST requests to perform the image search.
     * @param req The HTTP request containing the URL to search.
     * @param resp The HTTP response with the found image URLs in JSON format.
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
     * Sets CORS headers for the HTTP response.
     * @param resp The HttpServletResponse to which headers are added.
     */
    private void setResponseHeaders(HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
        resp.setContentType("application/json");
    }

    /**
     * Validates the URL provided in the HTTP request.
     * @param req The HttpServletRequest containing the URL parameter.
     * @param resp The HttpServletResponse used for error reporting.
     * @return The validated URL or null if validation fails.
     * @throws IOException If an input or output exception occurs.
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
     * Recursive method to crawl through the given URL up to a maximum depth.
     * @param url The URL to crawl.
     * @param depth The current depth of the crawl.
     * @param startDomain The domain from which the crawl started, used for limiting the scope of the search.
     * @throws IOException If an error occurs during HTTP fetching.
     */
    private void crawl(String url, int depth, String startDomain) throws IOException {
        if (depth > MAX_DEPTH) return;
        Document doc = fetchDocument(url);
        if (doc != null) {
            processImages(doc, startDomain);
            followLinks(doc, depth, startDomain);
        }
    }

    /**
     * Processes and queues the image URLs found in the document.
     * @param doc The JSoup Document object containing the HTML to parse.
     * @param startDomain The domain from which the crawl started.
     */
    private void processImages(Document doc, String startDomain) {
        Elements imageElements = doc.select("img[src]");
        for (Element image : imageElements) {
            String imageUrl = image.attr("abs:src");
            try {
                String imageDomain = getDomainName(imageUrl);
                if (imageDomain.equals(startDomain) && imageSet.putIfAbsent(imageUrl, true) == null) {
                    testImages.offer(imageUrl);
                }
            } catch (URISyntaxException e) {
                System.err.println("Error parsing URL: " + imageUrl);
            }
        }
    }

    /**
     * Follows links within the document to continue the crawl.
     * @param doc The document from which links are extracted.
     * @param depth The current depth of the crawl.
     * @param startDomain The domain from which the crawl started.
     * @throws IOException If an error occurs during HTTP fetching or processing.
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
     * Validates the link URL to ensure it is acceptable for crawling.
     * @param url The URL to validate.
     * @param startDomain The domain of the initial URL to match against.
     * @return true if the URL is valid and matches the start domain, otherwise false.
     */
    private boolean isValidLink(String url, String startDomain) {
        return (url.startsWith("http://") || url.startsWith("https://")) && !url.startsWith("mailto:");
    }

    /**
     * Fetches the HTML document from the specified URL using JSoup.
     * @param url The URL to connect to.
     * @return The fetched Document or null if an error occurs.
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
     * Extracts the domain name from a URL.
     * @param url The URL from which the domain name will be extracted.
     * @return The extracted domain name.
     * @throws URISyntaxException If the URL is malformed.
     */
    private String getDomainName(String url) throws URISyntaxException {
        URI uri = new URI(url);
        String domain = uri.getHost();
        if (domain == null) {
            throw new URISyntaxException(url, "Host is null");
        }
        return domain.startsWith("www.") ? domain.substring(4) : domain;
    }
}
