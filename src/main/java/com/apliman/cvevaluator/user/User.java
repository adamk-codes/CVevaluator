package com.apliman.cvevaluator.user;

import com.apliman.cvevaluator.job.Job;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id=null;

    private String name;

    @Column(unique = true, nullable = false)
    private String email;

    private String passwordHash;

    @Enumerated(EnumType.STRING)
    private Role role;

    private Instant createdAt;

    @OneToMany(mappedBy = "createdByRecruiter")
    private List<Job> listedJobs;

    protected User() {
    }

    public User(String name, String passwordHash, String email, Role role) {
        this.name = name;
        this.passwordHash = passwordHash;
        this.email = email;
        this.role = role;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<Job> getListedJobs() {
        return listedJobs;
    }
}