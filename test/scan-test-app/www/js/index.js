/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

// Wait for the deviceready event before using any of Cordova's device APIs.
// See https://cordova.apache.org/docs/en/latest/cordova/events/events.html#deviceready
document.addEventListener('deviceready', onDeviceReady, false);

// Tag used to filter this app's logs in `adb logcat chromium:I` /
// chrome://inspect.
const TAG = '[ScanTest]';
let scanCount = 0;

function log(text) {
  const node = document.createElement('div');
  node.textContent = text;
  document.getElementById('output').prepend(node);
}

// With the `multiple` option the callback receives an array of results;
// otherwise it receives a single result. Normalise to an array so both work.
function onSuccess(result) {
  // Log exactly what the plugin handed back so we can see its shape.
  console.log(TAG, 'onSuccess raw result:', JSON.stringify(result));

  const results = Array.isArray(result) ? result : [result];
  console.log(TAG, `onSuccess parsed ${results.length} barcode(s)`);

  results.forEach((barcode, i) => {
    console.log(
      TAG,
      `  [${i}] text=${barcode.text} format=${barcode.format} type=${barcode.type}`,
    );
    log(`${barcode.text} (${barcode.format}/${barcode.type})`);
  });
}

function onError(error) {
  console.log(TAG, 'onError:', JSON.stringify(error));
  log(error.cancelled ? 'Scanner closed' : `Error: ${error.message}`);
}

function scan() {
  const formData = new FormData(document.querySelector('form'));
  const options = {};

  for (const pair of formData.entries()) {
    const key = pair[0];
    const value = pair[1];
    options[key] = value === 'true';
  }

  scanCount += 1;
  console.log(TAG, `--- scan #${scanCount} ---`);
  console.log(TAG, 'options:', JSON.stringify(options));
  log(`Scanning... (#${scanCount})`);

  cordova.plugins.mlkit.barcodeScanner.scan(options, onSuccess, onError);
}

function onDeviceReady() {
  console.log(TAG, 'Running cordova-' + cordova.platformId + '@' + cordova.version);
  document.getElementById('scan').onclick = scan;
}
