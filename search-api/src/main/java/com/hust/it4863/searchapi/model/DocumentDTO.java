package com.hust.it4863.searchapi.model;

import java.util.List;

/**
 * Tai lieu da chuan hoa, dung chung cho moi nguon dau vao (.txt/.doc/.docx/.json,
 * hoac output cua crawler) truoc khi dua vao IndexingService.
 */
public class DocumentDTO {

    private String id;
    private String title;
    private String content;
    private List<OutLink> outlinks;

    public DocumentDTO() {
    }

    public DocumentDTO(String id, String title, String content, List<OutLink> outlinks) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.outlinks = outlinks;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<OutLink> getOutlinks() {
        return outlinks;
    }

    public void setOutlinks(List<OutLink> outlinks) {
        this.outlinks = outlinks;
    }
}
