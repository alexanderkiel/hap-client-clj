__This software is ALPHA.__

# HAP Client Clojure

A Clojure(Script) library which implements a generic Hypermedia Application 
Protocol (HAP) client.

## Install

To install, just add the following to your project dependencies:

```clojure
[org.clojars.akiel/hap-client-clj "0.1-SNAPSHOT"]
```

## Usage

### Fetch a Representation

```clojure
(require '[hap-client.core :as hap])
(hap/fetch (hap/resource "http://localhost:8080"))
```

### Create a new Resource

```clojure
(require '[clojure.core.async :refer [go]])
(require '[hap-client.async :refer [<?]])
(require '[hap-client.core :as hap])

(go
  (try
    (let [root (<? (hap/fetch (hap/resource "http://localhost:8080")))
          form (:my-form (:forms root))]
      (<? (hap/create form {:name "value"})))
  (catch Throwable t t)))
```

## License

Copyright © 2015 Alexander Kiel

Distributed under the Eclipse Public License, the same as Clojure.
