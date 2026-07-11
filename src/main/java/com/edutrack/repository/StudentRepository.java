package com.edutrack.repository;

import com.edutrack.model.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StudentRepository extends JpaRepository<Student, Long> {
    Optional<Student> findByQrCode(String qrCode);
    Optional<Student> findByStudentId(String studentId);
    List<Student> findByActiveTrue();
    List<Student> findByGradeAndActiveTrue(String grade);
    boolean existsByStudentId(String studentId);
}
