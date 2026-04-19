self.onmessage = function(e) {
  self.postMessage('Worker received: ' + e.data + ' — running on HTTP!');
};