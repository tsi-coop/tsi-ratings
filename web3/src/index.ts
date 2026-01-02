import express, { Request, Response, NextFunction } from 'express';
require('dotenv').config();
import bodyParser from 'body-parser';
// Adjust the path './transport/ExpressTransport' to the correct location.
import { Setup } from '@bsv/wallet-toolbox';
import { createPaymentMiddleware } from '@bsv/payment-express-middleware';
import { AuthRequest, createAuthMiddleware } from '@bsv/auth-express-middleware';
import * as crypto from 'crypto';
import { PrivateKey, WalletClient, PushDrop, OP, Utils, Transaction, LockingScript, type WalletOutput, WalletProtocol} from '@bsv/sdk';
import { Chain } from '@bsv/wallet-toolbox/out/src/sdk/types.js';
import { PubKeyHex, VerifiableCertificate } from '@bsv/sdk';
import { OpReturn } from '@bsv/templates';

// Global crypto polyfill needed for some environments
(global.self as any) = { crypto };

// --- Configuration Constants ---
const {
  SERVER_PRIVATE_KEY,
  WALLET_STORAGE_URL,
  HTTP_PORT,
  BSV_NETWORK,
  AUTH_USER,
  AUTH_SECRET
} = process.env;


// Function to validate critical environment variables
function validateEnvironment() {
  const missing = [];

  if (!SERVER_PRIVATE_KEY) missing.push('SERVER_PRIVATE_KEY');
  if (!WALLET_STORAGE_URL) missing.push('WALLET_STORAGE_URL');
  if (!HTTP_PORT) missing.push('HTTP_PORT');
  if (!BSV_NETWORK) missing.push('BSV_NETWORK');
  if (missing.length > 0) {
    throw new Error(`CRITICAL ERROR: The following required environment variables are missing from your .env file or environment: ${missing.join(', ')}`);
  }
}

// Ensure the variables are treated as strings for TypeScript
const SERVER_PRIVATE_KEY_STR: string = SERVER_PRIVATE_KEY as string;
const WALLET_STORAGE_URL_STR: string = WALLET_STORAGE_URL as string;
const HTTP_PORT_STR: string = HTTP_PORT as string;
const BSV_NETWORK_STR: string = BSV_NETWORK as string;

const app = express();
const basicAuth = require('express-basic-auth');

app.use(bodyParser.json({ limit: '64mb' }));

// Middleware to handle CORS
app.use((req: Request, res: Response, next: NextFunction) => {
  res.header('Access-Control-Allow-Origin', '*');
  res.header('Access-Control-Allow-Headers', '*');
  res.header('Access-Control-Allow-Methods', '*');
  res.header('Access-Control-Expose-Headers', '*');
  res.header('Access-Control-Allow-Private-Network', 'true');
  res.header('allowAuthenticated', 'false');
  if (req.method === 'OPTIONS') {
    res.sendStatus(200);
  } else {
    next();
  }
});

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
 * Converts two integer constants (msmeId and auditorId) into an array of numbers.
 * @param msmeId The ID of the MSME (integer).
 * @param auditorId The ID of the auditor (integer).
 * @returns An array containing both IDs: [msmeId, auditorId].
 */
function convertIdsToArray(msmeId: number, auditorId: number): number[] {
  // Directly return an array containing both integer constants.
  return [msmeId, auditorId];
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
  console.log('.env file fetched');

  // Initialize Wallet Client (Server Identity)
  const wallet = await Setup.createWalletClientNoEnv({
    chain: BSV_NETWORK_STR as Chain,
    rootKeyHex: SERVER_PRIVATE_KEY_STR,
    storageUrl: WALLET_STORAGE_URL_STR
  });
  console.log('wallet client initiated');

  // Setup Authentication Middleware (Ensures only Certified Auditors can use this endpoint)
  app.use(createAuthMiddleware({
    wallet,
    allowUnauthenticated: true, // Require a wallet client to interact
    logger: console,
    logLevel: 'debug'
  }))
  console.log('auth middleware initiated');

  app.use(createPaymentMiddleware({
    wallet,
    calculateRequestPrice: async (req) => {
      //return 1;
      return 260;
    }
  }))
  console.log('payment middleware initiated');
  // ---------------------------------------------------------------------------
  // /anchorTSI Endpoint - Receives final score and anchors hash to Blockchain
  // ---------------------------------------------------------------------------

  app.post('/anchor-tsi-rating', async (req: AuthRequest, res: Response) => {
    console.log('here1');
    // Setup the payment middleware.


    const identityKey = req.auth?.identityKey || '';
    console.log(identityKey);
    //const certs = CERTIFICATES_RECEIVED[identityKey];
    //console.log('Certificates from requester:', certs)
    debugger
    //if (certs && certs.some(cert => cert.type === CERTIFICATE_TYPE_ID)) {
        // --- Input Validation ---
        const tsiData = req.body.tsiData;

        const tsiHash = tsiData.tsiHash;
        console.log(tsiHash);

      /*   res.status(200).json({
                                    status: 'OK',
                                    description: 'TSI Rating Token Anchored.',
                                    details: '2445ad60ae786b92e8375f0ab739023975c9966c8c02c7d6b17c7cb52511dbad'
                                }); */


        try {
            // Then, just use your template with the SDK!
            const instance = new OpReturn()
            const response = await wallet.createAction({
              description: `TSI Rating Anchor for business`,
              outputs: [{
                satoshis: 1,
                lockingScript: instance.lock(tsiHash).toHex(),
                basket: 'TSI_RATING_DMA',
                outputDescription: 'TSI DMA Rating'
              }]
            })
            console.log('txid');
            console.log(response.txid);
            res.status(200).json({
                            status: 'OK',
                            description: 'TSI Rating Token Anchored.',
                            details: response.txid
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
      console.log('TSI Rating Blockchain Anchor listening on port', HTTP_PORT)
  })

}
init().catch(err => {
  console.error('Failed to start server:', err);
});