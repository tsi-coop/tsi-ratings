import express, { Request, Response, NextFunction } from 'express';
import bodyParser from 'body-parser';
import { Setup } from '@bsv/wallet-toolbox';
import { createPaymentMiddleware } from '@bsv/payment-express-middleware';
import { AuthRequest, createAuthMiddleware } from '@bsv/auth-express-middleware';
import * as crypto from 'crypto';
import { PubKeyHex, VerifiableCertificate } from '@bsv/sdk';
import { Chain } from '@bsv/wallet-toolbox/out/src/sdk/types.js';
// Import PushDrop for embedding data
import { PUSH_DATA, pushdrop } from '@pushdrop/miniscript';

// Global crypto polyfill needed for some environments
(global.self as any) = { crypto };

// --- Configuration Constants ---
const {
  SERVER_PRIVATE_KEY = 'f9b0f65b26f7adfc70d3819491b42506c07d8f150c55a1eb31efe3b4997edba3', // WARNING: TEST KEY - REPLACE IN PRODUCTION!
  WALLET_STORAGE_URL = 'https://storage.babbage.systems',
  HTTP_PORT = 8080,
  // Define a custom Certificate Type ID for Auditors (Example Placeholder)
  AUDITOR_CERT_TYPE_ID = 'TSI_AUDITOR_CERTIFICATE_ID_HERE',
  BSV_NETWORK = 'main',
  CERTIFIER_IDENTITY_KEY = '0220529dc803041a83f4357864a09c717daa24397cf2f3fc3a5745ae08d30924fd'
} = process.env;

const app = express();
app.use(bodyParser.json({ limit: '64mb' }));

// Middleware to handle CORS
app.use((req: Request, res: Response, next: NextFunction) => {
  res.header('Access-Control-Allow-Origin', '*');
  res.header('Access-Control-Allow-Headers', '*');
  res.header('Access-Control-Allow-Methods', '*');
  res.header('Access-Control-Expose-Headers', '*');
  res.header('Access-Control-Allow-Private-Network', 'true');
  if (req.method === 'OPTIONS') {
    res.sendStatus(200);
  } else {
    next();
  }
});

// Serve static files (if needed)
app.use(express.static('public'));


// --- Core TSI Anchoring Logic ---

// The structure for the data to be anchored
interface TsiData {
  msmeId: string;
  auditorId: string;
  finalScore: number;
  assessmentDate: string; // ISO string for immutability
  version: string; // DMA version used (e.g., v1)
}

/**
 * Generates a SHA-256 hash of the complete TSI data structure.
 * This hash is the immutable fingerprint anchored to the blockchain.
 * @param data The structured TSI data.
 * @returns The SHA-256 hash string.
 */
function createTsiHash(data: TsiData): string {
  // Use a stable JSON stringification to ensure the hash is consistent
  const dataString = JSON.stringify(data);
  return crypto.createHash('sha256').update(dataString).digest('hex');
}

// -----------------------------------------------------------------------------
// Wallet and Middleware Setup inside async init function
// -----------------------------------------------------------------------------

async function init() {
  // 1. Initialize Wallet Client (Server Identity)
  const wallet = await Setup.createWalletClientNoEnv({
    chain: BSV_NETWORK as Chain,
    rootKeyHex: SERVER_PRIVATE_KEY,
    storageUrl: WALLET_STORAGE_URL
  });

  // 2. Setup Authentication Middleware (Ensures only Certified Auditors can use this endpoint)
  app.use(createAuthMiddleware({
    wallet,
    allowUnauthenticated: false, // Require a wallet client to interact
    logger: console,
    logLevel: 'debug',
    certificatesToRequest: {
      certifiers: [CERTIFIER_IDENTITY_KEY],
      types: {
        // Request proof that the user is a certified TSI Auditor
        [AUDITOR_CERT_TYPE_ID]: ['verified-auditor']
      }
    },
    // We don't need to store certs for this use case, just validate them.
  }));

  // 3. Setup Payment Middleware (Optional: To cover the transaction fee)
  app.use(createPaymentMiddleware({
    wallet,
    calculateRequestPrice: async () => {
      return 1000 // Example fee to cover anchoring transaction costs (1000 sats)
    }
  }));

  // ---------------------------------------------------------------------------
  // /anchorTSI Endpoint - Receives final score and anchors hash to Blockchain
  // ---------------------------------------------------------------------------

  app.post('/anchorTSI', async (req: AuthRequest, res: Response) => {
    // --- Validation and Authorization Check ---
    const identityKey = req.auth?.identityKey || '';
    const certificates = req.auth?.certificates || [];

    // Check if the requesting party (IT Auditor) has the required certificate
    const isCertifiedAuditor = certificates.some(cert => cert.type === AUDITOR_CERT_TYPE_ID);

    if (!isCertifiedAuditor) {
      return res.status(401).json({
        status: 'error',
        description: 'Authorization failed: Only certified IT Auditors can anchor TSI scores.'
      });
    }

    // --- Input Validation ---
    const tsiData: TsiData = req.body.tsiData;
    const { msmeId, auditorId, finalScore, assessmentDate, version } = tsiData;

    if (!msmeId || !auditorId || typeof finalScore !== 'number' || !assessmentDate || !version) {
      return res.status(400).json({
        status: 'error',
        description: 'Invalid input data: Missing required fields for TSI anchoring.'
      });
    }

    // Ensure the auditorId submitted matches the authenticated identity key
    if (auditorId !== identityKey) {
        return res.status(403).json({
            status: 'error',
            description: 'Forbidden: Auditor ID mismatch with authenticated identity key.'
        });
    }

    // --- Hashing and Transaction ---

    try {
        // 1. Create the immutable hash
        const tsiHash = createTsiHash(tsiData);

        // 2. Define the PushDrop data protocol (Array of Buffers or Strings)
        // Protocol: [TSI_PROTOCOL_ID, SHA256_HASH]
        const tsiProtocolId = 'TSI'; // Simple Protocol Identifier
        const pushdropData: PUSH_DATA[] = [tsiProtocolId, tsiHash];

        // 3. Generate the PushDrop locking script (the destination for the funds)
        const lockingScript = pushdrop.create({ payload: pushdropData });

        // 4. Send the anchoring transaction, using the PushDrop script
        const tx = await wallet.send({
            description: `TSI Rating Anchor for MSME:${msmeId} (PushDrop)`,
            outputs: [{
                // The output will be paid to the PushDrop script containing the data
                script: lockingScript,
                satoshis: 1 // Minimal amount needed for PushDrop transaction
            }]
        });

        // 5. Success Response
        res.json({
            status: 'success',
            message: 'TSI Score successfully anchored using PushDrop.',
            tsiHash: tsiHash,
            blockchainTxId: tx.txid,
            protocol: tsiProtocolId,
            lockingScript: lockingScript.toHex()
        });

    } catch (error) {
        console.error('Blockchain anchoring failed:', error);
        res.status(500).json({
            status: 'error',
            description: 'Failed to create blockchain anchor transaction.',
            details: (error as Error).message
        });
    }
  });

  // Start the server.
  app.listen(HTTP_PORT, () => {
    console.log('TSI Blockchain Anchor Service listening on port', HTTP_PORT);
  });
}

init().catch(err => {
  console.error('Failed to start server:', err);
});
