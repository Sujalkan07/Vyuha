package com.vyuha.reporting;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportExportRecordRepository extends JpaRepository<ReportExportRecord, Long> {

    List<ReportExportRecord> findTop20ByEventIdOrderByAuditCreatedAtDescIdDesc(Long eventId);
}
