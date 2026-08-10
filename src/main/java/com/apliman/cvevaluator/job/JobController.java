package com.apliman.cvevaluator.job;

import com.apliman.cvevaluator.job.dto.CreateJobRequest;
import com.apliman.cvevaluator.job.dto.JobResponse;
import com.apliman.cvevaluator.job.dto.UpdateJobRequirementsRequest;
import com.apliman.cvevaluator.security.HeaderCurrentUserProvider;
import com.apliman.cvevaluator.user.User;
import com.apliman.cvevaluator.user.UserRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobRepository repo;
    private final UserRepository userRepo;
    private final HeaderCurrentUserProvider idProvider;
    private final JobRequirementsValidator requirementsValidator;
    private final ApplicationReevaluationTrigger reevaluationTrigger;

    public JobController(
            JobRepository repo,
            UserRepository userRepo,
            HeaderCurrentUserProvider idProvider,
            JobRequirementsValidator requirementsValidator,
            ApplicationReevaluationTrigger reevaluationTrigger
    ){
        this.repo=repo;
        this.userRepo=userRepo;
        this.idProvider=idProvider;
        this.requirementsValidator=requirementsValidator;
        this.reevaluationTrigger=reevaluationTrigger;
    }

    @PostMapping
    public ResponseEntity<JobResponse> createJob(@Valid @RequestBody CreateJobRequest request){
        User recruiter = userRepo.findById(idProvider.currentUserId())
                .orElseThrow(() -> new IllegalStateException("Current user not found"));

        // Validated before the entity is built, not after, so a rejected request
        // never reaches repo.save(). Requirements are authored by the recruiter
        // and never inferred here - nothing in this method asks an LLM anything.
        List<JobRequirement> requirements = requirementsValidator.validateAndNormalise(request.requirements());

        Job job=new Job(request.title(),request.description(),request.requirementsText(),
                request.seniority(), requirements, recruiter);
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

    /**
     * Replaces the whole requirements list.
     *
     * <p>PUT and not PATCH because the body is the new list entire — there is no
     * partial edit of a requirements set, and a client that sent three
     * requirements to a job that had eight means to be left with three.
     *
     * <p>Re-validated with the same rules as creation. That is the point of
     * {@link JobRequirementsValidator} being a collaborator rather than inline
     * code: a job cannot be edited into a state it could not have been created
     * in.
     *
     * <p><strong>Look here when the trigger stops being a no-op.</strong> The
     * trigger is called after {@code save} but inside no transaction of this
     * method's own, so {@code save} has already committed by the time it fires
     * and an async implementation reading the job back will see the new version.
     * Wrapping this method in {@code @Transactional} would quietly break that —
     * the trigger would then run inside the open transaction and a worker on
     * another thread could look for a version that has not been committed yet.
     * That failure is a race: it passes locally every time. If a transaction is
     * ever needed here, move the trigger to an AFTER_COMMIT event, the way
     * {@code CvIngestionService} already consumes {@code ApplicationCreatedEvent}.
     */
    @PutMapping("/{id}/requirements")
    public ResponseEntity<JobResponse> replaceRequirements(
            @PathVariable Long id,
            @Valid @RequestBody UpdateJobRequirementsRequest request
    ) {
        Job job = repo.findById(id)
                .orElseThrow(() -> new JobNotFoundException(id));

        List<JobRequirement> requirements = requirementsValidator.validateAndNormalise(request.requirements());

        job.replaceRequirements(requirements);
        Job saved = repo.save(job);

        reevaluationTrigger.onRequirementsChanged(saved);

        return ResponseEntity.ok(toResponse(saved));
    }

    private JobResponse toResponse(Job job) {
        return new JobResponse(
                job.getId(),
                job.getTitle(),
                job.getDescription(),
                job.getRequirementsText(),
                job.getSeniority(),
                job.getRequirements(),
                job.getRequirementsVersion(),
                job.getCreatedAt(),
                job.isActive()
        );
    }
}
