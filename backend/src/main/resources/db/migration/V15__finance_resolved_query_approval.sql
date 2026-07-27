-- A resolved Procurement clarification does not mutate represented invoice
-- evidence. The application already validates the exact current package and
-- readiness lineage before approval, so allow the corresponding state change.
CREATE OR REPLACE FUNCTION f05_guard_invoice_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    allowed BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE'
       OR OLD.id <> NEW.id
       OR OLD.engagement_month_id <> NEW.engagement_month_id
       OR OLD.vendor_organization_id <> NEW.vendor_organization_id
       OR OLD.invoice_type <> NEW.invoice_type
       OR OLD.invoice_number <> NEW.invoice_number
       OR OLD.normalized_invoice_number <> NEW.normalized_invoice_number
       OR OLD.invoice_date <> NEW.invoice_date
       OR OLD.billing_period_start <> NEW.billing_period_start
       OR OLD.billing_period_end <> NEW.billing_period_end
       OR OLD.currency <> NEW.currency
       OR OLD.taxable_value IS DISTINCT FROM NEW.taxable_value
       OR OLD.tax_value IS DISTINCT FROM NEW.tax_value
       OR OLD.total_value IS DISTINCT FROM NEW.total_value
       OR OLD.po_reference IS DISTINCT FROM NEW.po_reference
       OR OLD.work_order_reference IS DISTINCT FROM NEW.work_order_reference
       OR OLD.created_by_subject <> NEW.created_by_subject
       OR OLD.created_at <> NEW.created_at
       OR OLD.correlation_id <> NEW.correlation_id
    THEN
        RAISE EXCEPTION 'F05 represented invoice fields are immutable'
            USING ERRCODE = '23514';
    END IF;

    allowed := OLD.status = NEW.status OR (OLD.status, NEW.status) IN (
        ('DRAFT', 'UPLOADED'),
        ('UPLOADED', 'EVIDENCE_PENDING'),
        ('UPLOADED', 'READY_FOR_VENDOR_SUBMISSION'),
        ('EVIDENCE_PENDING', 'UPLOADED'),
        ('EVIDENCE_PENDING', 'READY_FOR_VENDOR_SUBMISSION'),
        ('EVIDENCE_PENDING', 'EXCEPTION_ACCEPTED'),
        ('READY_FOR_VENDOR_SUBMISSION', 'EVIDENCE_PENDING'),
        ('SUBMITTED_TO_PROCUREMENT', 'EVIDENCE_PENDING'),
        ('PROCUREMENT_REVIEW', 'EVIDENCE_PENDING'),
        ('CHANGES_REQUESTED', 'EVIDENCE_PENDING'),
        ('ON_HOLD', 'EVIDENCE_PENDING'),
        ('REJECTED', 'EVIDENCE_PENDING'),
        ('EXCEPTION_ACCEPTED', 'EVIDENCE_PENDING'),
        ('APPROVED_FOR_PROCESSING', 'EVIDENCE_PENDING'),
        ('PAYMENT_INITIATED', 'EVIDENCE_PENDING'),
        ('READY_FOR_VENDOR_SUBMISSION', 'SUBMITTED_TO_PROCUREMENT'),
        ('SUBMITTED_TO_PROCUREMENT', 'PROCUREMENT_REVIEW'),
        ('SUBMITTED_TO_PROCUREMENT', 'APPROVED_FOR_PROCESSING'),
        ('SUBMITTED_TO_PROCUREMENT', 'CHANGES_REQUESTED'),
        ('SUBMITTED_TO_PROCUREMENT', 'ON_HOLD'),
        ('SUBMITTED_TO_PROCUREMENT', 'REJECTED'),
        ('PROCUREMENT_REVIEW', 'APPROVED_FOR_PROCESSING'),
        ('PROCUREMENT_REVIEW', 'CHANGES_REQUESTED'),
        ('PROCUREMENT_REVIEW', 'ON_HOLD'),
        ('PROCUREMENT_REVIEW', 'REJECTED'),
        ('SUBMITTED_TO_PROCUREMENT', 'EXCEPTION_ACCEPTED'),
        ('PROCUREMENT_REVIEW', 'EXCEPTION_ACCEPTED'),
        ('CHANGES_REQUESTED', 'APPROVED_FOR_PROCESSING'),
        ('CHANGES_REQUESTED', 'EXCEPTION_ACCEPTED'),
        ('ON_HOLD', 'EXCEPTION_ACCEPTED'),
        ('EXCEPTION_ACCEPTED', 'APPROVED_FOR_PROCESSING'),
        ('EXCEPTION_ACCEPTED', 'CHANGES_REQUESTED'),
        ('EXCEPTION_ACCEPTED', 'ON_HOLD'),
        ('EXCEPTION_ACCEPTED', 'REJECTED'),
        ('EXCEPTION_ACCEPTED', 'SUBMITTED_TO_PROCUREMENT'),
        ('CHANGES_REQUESTED', 'UPLOADED'),
        ('ON_HOLD', 'PROCUREMENT_REVIEW'),
        ('REJECTED', 'UPLOADED'),
        ('APPROVED_FOR_PROCESSING', 'PAYMENT_INITIATED'),
        ('APPROVED_FOR_PROCESSING', 'ON_HOLD'),
        ('PAYMENT_INITIATED', 'PAID'),
        ('PAYMENT_INITIATED', 'ON_HOLD'),
        ('ON_HOLD', 'PAYMENT_INITIATED'),
        ('PAID', 'CLOSED'),
        ('DRAFT', 'SUPERSEDED'),
        ('UPLOADED', 'SUPERSEDED'),
        ('EVIDENCE_PENDING', 'SUPERSEDED'),
        ('READY_FOR_VENDOR_SUBMISSION', 'SUPERSEDED'),
        ('CHANGES_REQUESTED', 'SUPERSEDED'),
        ('REJECTED', 'SUPERSEDED'),
        ('DRAFT', 'CANCELLED'),
        ('UPLOADED', 'CANCELLED'),
        ('EVIDENCE_PENDING', 'CANCELLED'),
        ('READY_FOR_VENDOR_SUBMISSION', 'CANCELLED')
    );
    IF NOT allowed THEN
        RAISE EXCEPTION 'Illegal F05 invoice state transition % -> %',
            OLD.status, NEW.status USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
