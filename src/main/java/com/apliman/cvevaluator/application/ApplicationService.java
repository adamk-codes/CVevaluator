package com.apliman.cvevaluator.application;

import com.apliman.cvevaluator.job.JobRepository;
import com.apliman.cvevaluator.storage.StoredFile;
import com.apliman.cvevaluator.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transaction boundary for creating an {@link Application}.
 *
 * <p>Per project rule, file I/O is never inside a transaction. That rule is
 * enforced by this class's signature rather than by a comment: {@link #create}
 * takes an already-written {@link StoredFile}, not a {@code MultipartFile}, so
 * there is nothing here to write to disk even by accident. The caller does the
 * write first, outside any transaction, and hands the result in.
 */
@Service
public class ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final JobRepository jobRepository;
    private final UserRepository userRepository;

    public ApplicationService(
            ApplicationRepository applicationRepository,
            JobRepository jobRepository,
            UserRepository userRepository
    ) {
        this.applicationRepository = applicationRepository;
        this.jobRepository = jobRepository;
        this.userRepository = userRepository;
    }

    /**
     * Inserts the row for a CV that is already on disk.
     *
     * <p>Both associations are resolved with {@code getReferenceById}, not
     * {@code findById}. Two reasons:
     *
     * <ul>
     *   <li>The insert only needs the foreign keys, so loading the full rows
     *       would be two SELECTs for columns nobody reads. With
     *       {@code show-sql=true} you should see the caller's existence checks
     *       and then the INSERT - and no SELECT from this method.
     *   <li>The caller checks existence <em>outside</em> this transaction. If it
     *       passed the loaded entities in, they would arrive detached and have
     *       to be merged back into this persistence context. Passing ids and
     *       taking proxies here avoids that entirely.
     * </ul>
     *
     * <p>The cost is that a nonexistent id is not caught here - it surfaces as a
     * foreign key violation at flush rather than a clean 404. The caller checks
     * first precisely so that path is unreachable in practice.
     */
    @Transactional
    public Application create(Long jobId, Long candidateId, StoredFile stored, String contentType) {
        Application application = new Application(
                jobRepository.getReferenceById(jobId),
                userRepository.getReferenceById(candidateId),
                stored.originalFilename(),
                contentType,
                stored.sizeBytes(),
                stored.storageKey()
        );
        return applicationRepository.save(application);
    }
}
