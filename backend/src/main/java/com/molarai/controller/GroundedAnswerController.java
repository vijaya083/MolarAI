package com.molarai.controller;

import com.molarai.dto.GroundedAnswerRequest;
import com.molarai.dto.GroundedAnswerResponse;
import com.molarai.service.GroundedResponseService;
import com.molarai.performance.PerformanceTiming;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge")
public class GroundedAnswerController {
    private final GroundedResponseService responseService;
    private final PerformanceTiming timing;

    public GroundedAnswerController(GroundedResponseService responseService, PerformanceTiming timing) {
        this.responseService = responseService;
        this.timing = timing;
    }

    @PostMapping("/answer")
    public GroundedAnswerResponse answer(@Valid @RequestBody GroundedAnswerRequest request) {
        GroundedAnswerResponse response = responseService.answer(request.query());
        timing.markSerializationStart();
        return response;
    }
}
