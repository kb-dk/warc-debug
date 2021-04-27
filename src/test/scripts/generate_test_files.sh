#!/bin/bash

#
# Generates Gzipped test files, emulating the compression pattern of
# WARC-files seen in the wild.
#

pushd ${BASH_SOURCE%/*} > /dev/null
: ${DEST:="$(pwd)/../resources/"}
popd > /dev/null

uncompressed() {
    echo "Uncompressed content" > ${DEST}/uncompressed.txt
    cp ${DEST}/uncompressed.txt ${DEST}/uncompressed_faulty_extension.txt.gz
}

fully_compressed() {
    echo "Compressed content" | gzip > ${DEST}/compressed.txt.gz
    cp ${DEST}/compressed.txt.gz ${DEST}/compressed_faulty_extension.txt
}

multi_compressed() {
    echo "Compressed content block 1 alabast" | gzip > ${DEST}/compressed_multi.txt.gz
    echo "Compressed content block 2 bentonite" | gzip >> ${DEST}/compressed_multi.txt.gz
    echo "Compressed content block 3 circumference" | gzip >> ${DEST}/compressed_multi.txt.gz
    echo "Compressed content block 4 delta" | gzip >> ${DEST}/compressed_multi.txt.gz
}

recompressed_multi_compressed() {
  if [[ ! -s ${DEST}/compressed_multi.txt.gz ]]; then
    multi_compressed
  fi
  cat ${DEST}/compressed_multi.txt.gz | gzip > ${DEST}/recompressed_compressed_multi.txt.gz.gz
}

partially_compressed_first() {
    echo "Compressed content" | gzip > ${DEST}/partial_first.txt.gz
    echo "Uncompressed content" >> ${DEST}/partial_first.txt.gz
    echo "More compressed content" | gzip >> ${DEST}/partial_first.txt.gz
    echo "More uncompressed content" >> ${DEST}/partial_first.txt.gz
}

partially_compressed_second() {
    echo "Uncompressed content" > ${DEST}/partial_second.txt.gz
    echo "Compressed content" | gzip >> ${DEST}/partial_second.txt.gz
    echo "More uncompressed content" >> ${DEST}/partial_second.txt.gz
    echo "More compressed content" | gzip >> ${DEST}/partial_second.txt.gz
}

uncompressed
fully_compressed
multi_compressed
partially_compressed_first
partially_compressed_second
recompressed_multi_compressed
echo "Test files generated and stored in $DEST"
