__This software is ALPHA.__

# HAP Client Clojure

[![Build Status](https://travis-ci.org/alexanderkiel/hap-client-clj.svg?branch=master)](https://travis-ci.org/alexanderkiel/hap-client-clj)

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

## Schema Support

HAP query and form params can have a `:type`. HAP Client Clojure supports the
following schemas specified as symbols or s-expressions by resolving them to
there [Prismatic Schema][1] equivalents.

 * [Str](http://prismatic.github.io/schema/schema.core.html#var-Str)
 * [Bool](http://prismatic.github.io/schema/schema.core.html#var-Bool)
 * [Num](http://prismatic.github.io/schema/schema.core.html#var-Num)
 * [Int](http://prismatic.github.io/schema/schema.core.html#var-Int)
 * [Keyword](http://prismatic.github.io/schema/schema.core.html#var-Keyword)
 * [Symbol](http://prismatic.github.io/schema/schema.core.html#var-Symbol)
 * [Regex](http://prismatic.github.io/schema/schema.core.html#var-Regex)
 * [Inst](http://prismatic.github.io/schema/schema.core.html#var-Inst)
 * [Uuid](http://prismatic.github.io/schema/schema.core.html#var-Uuid)
 * [either](http://prismatic.github.io/schema/schema.core.html#var-either) 
 * [both](http://prismatic.github.io/schema/schema.core.html#var-both) 
 * [enum](http://prismatic.github.io/schema/schema.core.html#var-enum) 

Unsupported forms are just passed through and not resolved. So library users
can't be sure that `:type` values are always schemas.

## License

Copyright © 2015 Alexander Kiel

Distributed under the Eclipse Public License, the same as Clojure.

[1]: <https://github.com/Prismatic/schema>
