// Instead of importing from bundle.js, use the global variable:
const { AuthFetch, WalletClient } = window.bsv

// Define the JSON payload to be sent
const tsiPayload = {
  "tsiData": {
    "msmeId": "1",
    "auditorId": "2",
    "finalScore": 91.5,
    "assessmentDate": "2025-11-04T10:30:00Z",
    "version": "v1",
    "type":"DMA"
  }
};

document.getElementById('anchor-button').addEventListener('click', async () => {
  try {
    // Create the wallet client and AuthFetch instance.
    const wallet = new WalletClient('json-api', 'localhost')
    const client = new AuthFetch(wallet)

    // Fetch weather stats using AuthFetch.
    const response = await client.fetch('http://localhost:8085/anchor-tsi-rating', {
      method: 'POST',
      // 1. Set the body to the JSON stringified payload
      body: JSON.stringify(tsiPayload),
      // 2. Set the Content-Type header
      headers: {
        'Content-Type': 'application/json'
      }
    })

    const data = await response.json()
    console.log('Result:', data)

    // Update the UI with the response data.
    document.getElementById('anchor-data').innerHTML = JSON.stringify(data, null, 2)
  } catch (error) {
    console.error('[ERROR] Failed to anchor data:', error)
    document.getElementById('anchor-data').innerHTML = `<span style="color: red;">ERROR:</span> ${error.message}`;
  }
})
