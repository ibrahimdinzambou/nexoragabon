package com.iptv.saas.web;

import com.iptv.saas.service.DramaApiService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dramas")
public class DramaController {
    private final DramaApiService dramas;

    public DramaController(DramaApiService dramas) {
        this.dramas = dramas;
    }

    @GetMapping("/bookshelves")
    public Object bookshelves(@RequestParam(defaultValue = "fr") String lang) {
        return Responses.ok(dramas.bookshelves(lang));
    }

    @GetMapping("/search")
    public Object search(
            @RequestParam String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "fr") String lang
    ) {
        return Responses.ok(dramas.search(q, page, lang));
    }

    @GetMapping("/episodes/{bookId}")
    public Object episodes(
            @PathVariable String bookId,
            @RequestParam("filtered_title") String filteredTitle,
            @RequestParam(defaultValue = "fr") String lang
    ) {
        return Responses.ok(dramas.episodes(bookId, filteredTitle, lang));
    }

    @GetMapping("/video/{bookId}/{episode}")
    public Object video(
            @PathVariable String bookId,
            @PathVariable int episode,
            @RequestParam("filtered_title") String filteredTitle,
            @RequestParam("chapter_id") String chapterId,
            @RequestParam(defaultValue = "fr") String lang
    ) {
        return Responses.ok(dramas.video(bookId, episode, filteredTitle, chapterId, lang));
    }
}
