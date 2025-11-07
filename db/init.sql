-- PostgreSQL Schema for TSI Ratings Platform

-- Set up the environment for case-insensitive role/status checks if needed (optional)
-- CREATE EXTENSION IF NOT EXISTS citext;

---
-- 1. User Table (Stores credentials, professional identity, and OTP state)
---
CREATE TABLE "User" (
    "userId" BIGSERIAL PRIMARY KEY,
    "email" VARCHAR(255) NOT NULL UNIQUE,
    "role" VARCHAR(50) NOT NULL CHECK ("role" IN ('admin', 'msme', 'auditor', 'lender')),

    -- New Professional Identity Fields
    "one_liner" VARCHAR(255),
    "linkedin" VARCHAR(255),

    -- Fields required for Email OTP Login Flow
    "otpCode" VARCHAR(6),
    "otpExpiry" TIMESTAMP WITH TIME ZONE,

    -- Audit Fields
    "last_login_at" TIMESTAMP WITH TIME ZONE,
    "createdAt" TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Index for efficient email lookup during login/OTP request
CREATE UNIQUE INDEX idx_user_email ON "User" ("email");
-- Index for efficient role-based queries
CREATE INDEX idx_user_role ON "User" ("role");


---
-- 2. MSME Table (Stores immutable details about the business being rated)
---
CREATE TABLE "MSME" (
    "msmeId" BIGINT PRIMARY KEY,
    "companyName" VARCHAR(255) NOT NULL,
    "udyamRegistrationNo" VARCHAR(50) UNIQUE,
    "industrySector" VARCHAR(100),
    "contactName" VARCHAR(100),
    "dateJoined" TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    -- Foreign Key Constraint linking MSME details to its User account
    CONSTRAINT fk_msme_user
        FOREIGN KEY ("msmeId")
        REFERENCES "User" ("userId")
        ON DELETE CASCADE
);


---
-- 3. DMA_Assessment Table (The central record of a completed Digital Maturity Assessment audit)
---
CREATE TABLE "DMA_Assessment" (
    "assessmentId" BIGSERIAL PRIMARY KEY,
    "msmeId" BIGINT NOT NULL,
    "auditorId" BIGINT NOT NULL,
    "finalTsiScore" NUMERIC(5, 2) NOT NULL,
    "completionDate" TIMESTAMP WITH TIME ZONE NOT NULL,
    "status" VARCHAR(50) NOT NULL CHECK ("status" IN ('PENDING', 'AUDITED', 'ANCHORED', 'EXPIRED')),

    -- JSONB fields for assessment data and questionnaire version control
    "requestFormJson" JSONB,
    "assessmentDetailJson" JSONB,

    -- Foreign Keys
    CONSTRAINT fk_dma_msme
        FOREIGN KEY ("msmeId")
        REFERENCES "MSME" ("msmeId")
        ON DELETE CASCADE,

    CONSTRAINT fk_dma_auditor
        FOREIGN KEY ("auditorId")
        REFERENCES "User" ("userId")
        ON DELETE SET NULL -- If auditor leaves, keep assessment but nullify auditor link
);

-- Index for efficient access to the assessment data JSON fields
CREATE INDEX idx_dma_assessment_json_gin ON "DMA_Assessment" USING GIN ("assessmentDetailJson");
CREATE INDEX idx_dma_assessment_msme ON "DMA_Assessment" ("msmeId");


---
-- 4. AnchorRecord Table (Provides the immutable proof link to the blockchain ledger)
---
CREATE TABLE "AnchorRecord" (
    "anchorId" BIGINT PRIMARY KEY, -- Links directly to DMA_Assessment.assessmentId
    "type" VARCHAR(10) NOT NULL CHECK ("type" IN ('DMA', 'CMA')), -- DMA or future CMA
    "blockchainTxId" VARCHAR(255) UNIQUE NOT NULL,
    "tsiHash" VARCHAR(64) NOT NULL,
    "anchorDate" TIMESTAMP WITH TIME ZONE NOT NULL,
    "blockchainNetwork" VARCHAR(20),

    -- Foreign Key Constraint linking the anchor proof back to the specific assessment
    CONSTRAINT fk_anchor_dma_assessment
        FOREIGN KEY ("anchorId")
        REFERENCES "DMA_Assessment" ("assessmentId")
        ON DELETE CASCADE
);

-- Index for fast blockchain verification lookups
CREATE INDEX idx_anchor_txid ON "AnchorRecord" ("blockchainTxId");
