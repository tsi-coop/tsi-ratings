import express, { Request, Response, NextFunction } from 'express';
require('dotenv').config();
import bodyParser from 'body-parser';
// Adjust the path './transport/ExpressTransport' to the correct location.
import { Setup } from '@bsv/wallet-toolbox';
import { createPaymentMiddleware } from '@bsv/payment-express-middleware';
import { AuthRequest, createAuthMiddleware } from '@bsv/auth-express-middleware';
import * as crypto from 'crypto';
import { WalletClient, PushDrop, Utils, Transaction, LockingScript, type WalletOutput, WalletProtocol} from '@bsv/sdk';
import { Chain } from '@bsv/wallet-toolbox/out/src/sdk/types.js';
import { PubKeyHex, VerifiableCertificate } from '@bsv/sdk'

// Global crypto polyfill needed for some environments
(global.self as any) = { crypto };

// --- Configuration Constants ---
const {
  SERVER_PRIVATE_KEY,
  WALLET_STORAGE_URL,
  HTTP_PORT,
  BSV_NETWORK,
  AUTH_USER,
  AUTH_SECRET,
  CERTIFICATE_TYPE_ID = 'AGfk/WrT1eBDXpz3mcw386Zww2HmqcIn3uY6x4Af1eo=',
  CERTIFIER_IDENTITY_KEY = '4g+2KE5u6IpG9YUszhiB/ttbmfj29bi5vxkMdOAfXCE='
} = process.env;

const CERTIFICATES_RECEIVED: Record<PubKeyHex, VerifiableCertificate[]> = {}

// Function to validate critical environment variables
function validateEnvironment() {
  const missing = [];

  if (!SERVER_PRIVATE_KEY) missing.push('SERVER_PRIVATE_KEY');
  if (!WALLET_STORAGE_URL) missing.push('WALLET_STORAGE_URL');
  if (!HTTP_PORT) missing.push('HTTP_PORT');
  if (!BSV_NETWORK) missing.push('BSV_NETWORK');
  if (!AUTH_USER) missing.push('AUTH_USER');
  if (!AUTH_SECRET) missing.push('AUTH_SECRET');


  if (missing.length > 0) {
    throw new Error(`CRITICAL ERROR: The following required environment variables are missing from your .env file or environment: ${missing.join(', ')}`);
  }
}

// Ensure the variables are treated as strings for TypeScript
const SERVER_PRIVATE_KEY_STR: string = SERVER_PRIVATE_KEY as string;
const WALLET_STORAGE_URL_STR: string = WALLET_STORAGE_URL as string;
const HTTP_PORT_STR: string = HTTP_PORT as string;
const BSV_NETWORK_STR: string = BSV_NETWORK as string;
const AUTH_USER_STR: string = AUTH_USER as string;
const AUTH_SECRET_STR: string = AUTH_SECRET as string;

const app = express();
const basicAuth = require('express-basic-auth');

// Configure the basic authentication middleware
app.use(basicAuth({
    users: { 'admin':'supersecret' },
    challenge: true, // This makes the browser prompt for credentials
    unauthorizedResponse: 'Unauthorized access. Please provide valid credentials.'
}));


app.use(bodyParser.json({ limit: '64mb' }));

// Middleware to handle CORS
app.use((req: Request, res: Response, next: NextFunction) => {
  console.log(req.method);
  res.header('Access-Control-Allow-Origin', '*');
  res.header('Access-Control-Allow-Headers', '*');
  res.header('Access-Control-Allow-Methods', '*');
  res.header('Access-Control-Expose-Headers', '*');
  res.header('Access-Control-Allow-Private-Network', 'true');
  res.header('X-BSV-Payment', '1000');
  if (req.method === 'OPTIONS') {
    console.log('options');
    res.sendStatus(200);
  } else {
    console.log('next');
    next();
  }
  console.log('here-c');
});


// Serve static files (if needed)
app.use(express.static('public'));

// --- Core TSI Anchoring Logic ---

// The structure for the data to be anchored
interface TsiData {
  msmeId: string;
  auditorId: string;
  finalScore: number;
  assessmentDate: string;
  version: string;
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
  // Validate environment
  try {
      validateEnvironment();
    } catch (err) {
      console.error(err);
      process.exit(1); // Exit the process if validation fails
    }

  // Initialize Wallet Client (Server Identity)
  const wallet = await Setup.createWalletClientNoEnv({
    chain: BSV_NETWORK_STR as Chain,
    rootKeyHex: SERVER_PRIVATE_KEY_STR,
    storageUrl: WALLET_STORAGE_URL_STR
  });

  // Setup Authentication Middleware (Ensures only Certified Auditors can use this endpoint)
  app.use(createAuthMiddleware({
    wallet,
    allowUnauthenticated: false, // Require a wallet client to interact
    logger: console,
    logLevel: 'debug',
    certificatesToRequest: {
          certifiers: [CERTIFIER_IDENTITY_KEY],
          types: {
            [CERTIFICATE_TYPE_ID]: ['cool']
          }
        },
    // Save certificates correctly when received.
    onCertificatesReceived: (
      senderPublicKey: string,
      certs: VerifiableCertificate[],
      req: AuthRequest,
      res: Response,
      next: NextFunction
    ) => {
      console.log('CERTS RECEIVED', certs)
      if (!CERTIFICATES_RECEIVED[senderPublicKey]) {
        CERTIFICATES_RECEIVED[senderPublicKey] = []
      }
      CERTIFICATES_RECEIVED[senderPublicKey].push(...certs)
      // next()
    }
  }))

  // ---------------------------------------------------------------------------
  // /anchorTSI Endpoint - Receives final score and anchors hash to Blockchain
  // ---------------------------------------------------------------------------

  app.post('/anchor-tsi-rating', async (req: AuthRequest, res: Response) => {
    console.log('here1');
    const identityKey = req.auth?.identityKey || '';
    console.log(identityKey);
    const certs = CERTIFICATES_RECEIVED[identityKey];
    console.log('Certificates from requester:', certs)
    debugger
    if (certs && certs.some(cert => cert.type === CERTIFICATE_TYPE_ID)) {
        // --- Input Validation ---
        const tsiData: TsiData = req.body.tsiData;
        const { msmeId, auditorId, finalScore, assessmentDate, version } = tsiData;

        if (!msmeId || !auditorId || typeof finalScore !== 'number' || !assessmentDate || !version) {
          return res.status(400).json({
            status: 'error',
            description: 'Invalid input data: Missing required fields for TSI anchoring.'
          });
        }

        // --- Hashing and Transaction ---

        try {
            // 1. Create the immutable hash
            const tsiHash = createTsiHash(tsiData);

            // 2. Define the PushDrop data protocol (Array of Buffers or Strings)
            // Protocol: [TSI_PROTOCOL_ID, SHA256_HASH]
            const TSI_RATING_PROTO_ADDR = '1ToDoDtKreEzbHYKFjmoBuduFmSXXUGZG'
            const PROTOCOL_ID: WalletProtocol = [0, 'TSI RATING'];
            const KEY_ID = '1';

           const hashedRating = (await wallet.encrypt({
           plaintext: Utils.toArray(tsiHash, 'utf8'),
           protocolID: PROTOCOL_ID,
           keyID: KEY_ID
          })).ciphertext
           console.log(hashedRating);

            const pushdrop = new PushDrop(wallet)
            const bitcoinOutputScript = await pushdrop.lock(
                [ // The "fields" are the data payload to attach to the token.
                  Utils.toArray(TSI_RATING_PROTO_ADDR, 'utf8'),
                  hashedRating
                ],
                PROTOCOL_ID,
                KEY_ID,
                'self'
            )

            // Create a token which represents an event ticket
            const response = await wallet.createAction({
              description: `TSI Rating Anchor for business:${msmeId} (PushDrop)`,
              outputs: [{
                satoshis: 1,
                lockingScript: bitcoinOutputScript.toHex(),
                basket: 'TSI_RATING_DMA',
                outputDescription: 'TSI DMA Rating'
              }]
            })
        } catch (error) {
            console.error('Blockchain anchoring failed:', error);
            res.status(500).json({
                status: 'error',
                description: 'Failed to create blockchain anchor transaction.',
                details: (error as Error).message
            });
        }
    }
  });

  app.post('/api/v1/verification/anchor', async (req: AuthRequest, res: Response) => {
      // --- Input Validation ---
      const tsiData: TsiData = req.body.tsiData;
      const txid: string = req.body.txid;

      if (!txid || typeof txid !== 'string' || txid.length !== 64) {
        return res.status(400).json({
          status: 'error',
          description: 'Invalid input data: Missing or malformed transaction ID (txid).'
        });
      }

      const { msmeId, auditorId, finalScore, assessmentDate, version } = tsiData;

      if (!msmeId || !auditorId || typeof finalScore !== 'number' || !assessmentDate || !version) {
        return res.status(400).json({
          status: 'error',
          description: 'Invalid input data: Missing required fields for TSI data verification.'
        });
      }

      try {
        // 1. Hashing the Presented Data
        const presentedHash = createTsiHash(tsiData);
        console.log('Presented Hash:', presentedHash);

        // 2. Simulate Retrieval and Decryption of the Anchor Hash
        // NOTE: In a real application, this step involves complex logic:
        // a) Fetching the transaction (txid) from a Bitcoin service (e.g., WhatsOnChain).
        // b) Parsing the PushDrop output script to extract the encrypted data.
        // c) Using wallet.decrypt() with the correct protocolID and keyID.

        // --- MOCK IMPLEMENTATION START ---
        let originalAnchoredHash: string;

        console.warn('NOTE: Using a mock for blockchain retrieval and decryption.');

        if (txid === '0000000000000000000000000000000000000000000000000000000000000001') {
            // This is a dummy hash representing what would be retrieved and decrypted
            // if the retrieval was successful.
            originalAnchoredHash = '990f77b7f1e56b3e95454687d6050529d84f4755104fa2c7df7c6e081c7268d0';
        } else {
            // Mocking a transaction not found or invalid
            console.error('Mock retrieval failed for TXID:', txid);
            return res.status(404).json({
                status: 'error',
                description: 'Anchor transaction could not be found or retrieved.',
                details: `Mock retrieval failed for txid: ${txid}`
            });
        }
        // --- MOCK IMPLEMENTATION END ---

        console.log('Original Anchored Hash (Simulated):', originalAnchoredHash);


        // 3. Comparing Hashes
        const match = presentedHash === originalAnchoredHash;
        const resultStatus = match ? 'MATCH' : 'MISMATCH';

        // 4. Send Response
        res.status(200).json({
          status: 'success',
          result: resultStatus,
          msmeId: msmeId,
          presentedHash: presentedHash,
          anchoredHash: originalAnchoredHash,
          txid: txid,
          description: match ? 'The presented data hash matches the immutable blockchain anchor.' : 'The presented data hash DOES NOT match the immutable blockchain anchor.'
        });

      } catch (error) {
        console.error('TSI Verification failed:', error);
        res.status(500).json({
          status: 'error',
          description: 'Failed to complete blockchain verification process.',
          details: (error as Error).message
        });
      }
    });

  // Start the server.
  app.listen(HTTP_PORT_STR, () => {
    console.log('TSI Blockchain Anchor Service listening on port', HTTP_PORT);
  });
}

init().catch(err => {
  console.error('Failed to start server:', err);
});
