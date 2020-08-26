# warc-debug

Performs analysis of [WARC](https://iipc.github.io/warc-specifications/specifications/warc-format/warc-1.0/) files
to determine well-formedness from a compression viewpoint.

[JWAT-Tools](https://github.com/netarchivesuite/jwat-tools) is much more in-depth, but does not have the same focus
on multi-entry compression. Merging the warc-debug code into JWAT-Tools might be a good idea.
  

## Requirements
 
 * Maven 3 (for building)
 * Java 11
 * Basic knowledge on WARC files

## What's the challenge?

A bit simplified: WARC files contains entries of HTTP requests and HTTP responses with extra meta data (WARC headers).
A WARC file can be gzip compressed, **but** this compression must be in individual request & response entrys in order
for playback tools to be able to perform random access lookup for individual entries.    

History shows that some tools have problems producing the compressed WARC files correctly.

## Usage

warc-debug takes a WARC file and performs a compression analysis. There is currently no focus on other well-formedness
parameters, such as HTTP-headers matching content length and similar.

warc-debug is capable of detecting

 * Mismatch between file name extension and compression (if `foo.warc` is compressed, it should be named `foo.warc.gz`)
 * Gzip errors in the compression stream (truncation or failing checksums)   
 * Illegal interleaving of compressed and non-compressed entries
 * Single-block compression of the full WARC, making random access impossible
  * If the WARC is single-block compressed, warc-debug will uncompress the stream to see if the result is a well-formed
    WARC        

In all cases a report with status for the WARC and a recommendation for possible action will be given.

## Building

```
mvn package
```

will produce 
```
target/warc-debug-0.1-SNAPSHOT-distribution.tar.gz
```

Uncompressing that file will give access to the script
```
bin/probe_compression.sh
```
When called with a WARC file, it will provide information such as

```
Processing '/home/te/projects/so-me/twitter/harvests/twitter_users_follow_tweets_20200818-1346.resources.warc.gz'...
GzipReport(status=multiCompressed, #entries=32, compressed=1,417,606 bytes, uncompressed=1,426,319 bytes, exception=null)
Advice: Everything seems to be in order (multi-entry compressed WARC file)

All entries:
Entry #0: source(0->508), compressed=508 bytes, uncompressed=750 bytes, snippet=WARC/1.0\r\nWARC-Type: warcinfo\r
Entry #1: source(508->982), compressed=474 bytes, uncompressed=674 bytes, snippet=WARC/1.0\r\nWARC-Type: request\r\n
Entry #2: source(982->124,206), compressed=123,224 bytes, uncompressed=125,453 bytes, snippet=WARC/1.0\r\nWARC-Type: response\r
Entry #3: source(124,206->124,642), compressed=436 bytes, uncompressed=598 bytes, snippet=WARC/1.0\r\nWARC-Type: request\r\n
Entry #4: source(124,642->132,097), compressed=7,455 bytes, uncompressed=8,689 bytes, snippet=WARC/1.0\r\nWARC-Type: response\r
Entry #5: source(132,097->132,572), compressed=475 bytes, uncompressed=675 bytes, snippet=WARC/1.0\r\nWARC-Type: request\r\n
Entry #6: source(132,572->255,796), compressed=123,224 bytes, uncompressed=125,454 bytes, snippet=WARC/1.0\r\nWARC-Type: response\r
Entry #7: source(255,796->256,252), compressed=456 bytes, uncompressed=637 bytes, snippet=WARC/1.0\r\nWARC-Type: request\r\n
Entry #8: source(256,252->290,918), compressed=34,666 bytes, uncompressed=43,602 bytes, snippet=WARC/1.0\r\nWARC-Type: response\r
Entry #9: source(290,918->291,381), compressed=463 bytes, uncompressed=651 bytes, snippet=WARC/1.0\r\nWARC-Type: request\r\n
Entry #10: source(291,381->293,890), compressed=2,509 bytes, uncompressed=3,297 bytes, snippet=WARC/1.0\r\nWARC-Type: response\r
Entry #11: source(293,890->294,346), compressed=456 bytes, uncompressed=637 bytes, snippet=WARC/1.0\r\nWARC-Type: request\r\n
Entry #12: source(294,346->299,624), compressed=5,278 bytes, uncompressed=8,641 bytes, snippet=WARC/1.0\r\nWARC-Type: response\r
Entry #13: source(299,624->300,086), compressed=462 bytes, uncompressed=651 bytes, snippet=WARC/1.0\r\nWARC-Type: request\r\n
Entry #14: source(300,086->302,393), compressed=2,307 bytes, uncompressed=3,145 bytes, snippet=WARC/1.0\r\nWARC-Type: response\r
Entry #15: source(302,393->302,848), compressed=455 bytes, uncompressed=637 bytes, snippet=WARC/1.0\r\nWARC-Type: request\r\n
Entry #16: source(302,848->327,943), compressed=25,095 bytes, uncompressed=25,980 bytes, snippet=WARC/1.0\r\nWARC-Type: response\r
Entry #17: source(327,943->328,406), compressed=463 bytes, uncompressed=651 bytes, snippet=WARC/1.0\r\nWARC-Type: request\r\n
Entry #18: source(328,406->330,684), compressed=2,278 bytes, uncompressed=3,145 bytes, snippet=WARC/1.0\r\nWARC-Type: response\r
Entry #19: source(330,684->331,142), compressed=458 bytes, uncompressed=637 bytes, snippet=WARC/1.0\r\nWARC-Type: request\r\n
Entry #20: source(331,142->359,134), compressed=27,992 bytes, uncompressed=33,901 bytes, snippet=WARC/1.0\r\nWARC-Type: response\r
Entry #21: source(359,134->359,596), compressed=462 bytes, uncompressed=651 bytes, snippet=WARC/1.0\r\nWARC-Type: request\r\n
Entry #22: source(359,596->362,139), compressed=2,543 bytes, uncompressed=3,373 bytes, snippet=WARC/1.0\r\nWARC-Type: response\r
Entry #23: source(362,139->362,594), compressed=455 bytes, uncompressed=635 bytes, snippet=WARC/1.0\r\nWARC-Type: request\r\n
Entry #24: source(362,594->1,266,116), compressed=903,522 bytes, uncompressed=906,267 bytes, snippet=WARC/1.0\r\nWARC-Type: response\r
Entry #25: source(1,266,116->1,266,572), compressed=456 bytes, uncompressed=635 bytes, snippet=WARC/1.0\r\nWARC-Type: request\r\n
Entry #26: source(1,266,572->1,295,961), compressed=29,389 bytes, uncompressed=29,889 bytes, snippet=WARC/1.0\r\nWARC-Type: response\r
Entry #27: source(1,295,961->1,296,405), compressed=444 bytes, uncompressed=613 bytes, snippet=WARC/1.0\r\nWARC-Type: request\r\n
Entry #28: source(1,296,405->1,414,999), compressed=118,594 bytes, uncompressed=119,004 bytes, snippet=WARC/1.0\r\nWARC-Type: response\r
Entry #29: source(1,414,999->1,415,314), compressed=315 bytes, uncompressed=425 bytes, snippet=WARC/1.0\r\nWARC-Type: metadata\r
Entry #30: source(1,415,314->1,415,791), compressed=477 bytes, uncompressed=694 bytes, snippet=WARC/1.0\r\nWARC-Type: resource\r
Entry #31: source(1,415,791->1,417,606), compressed=1,815 bytes, uncompressed=10,528 bytes, snippet=WARC/1.0\r\nWARC-Type: resource\r
```
