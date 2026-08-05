package com.apliman.cvevaluator.job;

import com.apliman.cvevaluator.job.dto.CreateJobRequest;
import com.apliman.cvevaluator.job.dto.JobResponse;
import com.apliman.cvevaluator.security.HeaderCurrentUserProvider;
import com.apliman.cvevaluator.user.User;
import com.apliman.cvevaluator.user.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobRepository repo;
    private final UserRepository userRepo;
    private final HeaderCurrentUserProvider idProvider;

    public JobController(JobRepository repo, UserRepository userRepo, HeaderCurrentUserProvider idProvider){
        this.repo=repo;
        this.userRepo=userRepo;
        this.idProvider=idProvider;
    }

    @PostMapping
    public ResponseEntity<JobResponse> createJob(@RequestBody CreateJobRequest request){
        User recruiter = userRepo.findById(idProvider.currentUserId())
                .orElseThrow(() -> new IllegalStateException("Current user not found"));
        Job job=new Job(request.title(),request.description(),request.requirements(),request.seniority(), recruiter);
        Job saved=repo.save(job);
        return ResponseEntity.status(201).body(toResponse(saved));
    }

    @GetMapping
    public List<JobResponse> getAllJobs() {
        return repo.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobResponse> getJobById(@PathVariable Long id) {
        Job job = repo.findById(id)
                .orElseThrow(() -> new JobNotFoundException(id));

        return ResponseEntity.ok(toResponse(job));
    }

    private JobResponse toResponse(Job job) {
        return new JobResponse(
                job.getId(),
                job.getTitle(),
                job.getDescription(),
                job.getRequirements(),
                job.getSeniority(),
                job.getCreatedAt(),
                job.isActive()
        );
    }
}
