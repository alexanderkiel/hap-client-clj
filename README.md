__This software is ALPHA.__

# HAP Client Clojure

[![Build Status](https://travis-ci.org/alexanderkiel/hap-client-clj.svg?branch=master)](https://travis-ci.org/alexanderkiel/hap-client-clj)

A Clojure(Script) library which implements a generic Hypermedia Application
Protocol (HAP) client.

## Install

To install, just add the following to your project dependencies:

```clojure
[org.clojars.akiel/hap-client-clj "0.5.1"]
```

## Usage

### Fetch a Representation

```clojure
(require '[hap-client.core :as hap])
(hap/fetch "http://localhost:8080")
```

### Create a new Resource

```clojure
(require '[clojure.core.async :refer [go]])
(require '[hap-client.async :refer [<?]])
(require '[hap-client.core :as hap])

(go
  (try
    (let [root (<? (hap/fetch "http://localhost:8080"))
          form (:my-form (:forms root))]
      (<? (hap/create form {:name "value"})))
  (catch Throwable t t)))
```

## Schema Support

HAP query and form params can have a `:type` which carries a [Schema][1]. HAP
Client Clojure supports all schemas which are supported by the
[Transit Schema][2] lib.

## License

Copyright © 2015 Alexander Kiel

Distributed under the Eclipse Public License, the same as Clojure.

[1]: <https://github.com/Prismatic/schema>
[2]: <https://github.com/alexanderkiel/transit-schema>
