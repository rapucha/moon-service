package dev.moonservice.backend.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class WebPageController {
    @GetMapping({"/", "/search"})
    String searchPage() {
        return "forward:/index.html";
    }
}
