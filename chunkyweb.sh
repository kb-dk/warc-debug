#!/bin/bash

#
# An extremely simple webserver in bash with the sole purpose of sending
# chunked and gzip-compressed responses.
#
# Adapted from the solutions at
# https://stackoverflow.com/questions/16640054/minimal-web-server-using-netcat
#
# Created by Toke Eskildsen with his Royal Danish Library-hat on, toes@kb.dk
# Apache License, Version 2.0
#

#
# Note: This is not a robust webserver. If stressed, it will not respond.
#

#
# To harvest to a WARC file with wget:
# 1) Start chunkyweb.sh and call
# 2) wget --wait 1 --warc-file="chunkyweb_wget" --mirror "http://localhost:8090/"
#    which should produce chunkyweb_wget.warc.gz
# Note the "--wait 1": chunkyweb is fragile and should be treated gentle.
#


: ${PORT:="$1"}
: ${PORT:="8090"}

: ${CONTENT:="Sample content abcdefghijklmnopqrstuvwxyz"}

CR=$(printf '\x0d')

direct_content() {
    local CONTENT="$1"

    echo "Content-Length: $(wc -c <<< "$CONTENT")"
    echo ""
    echo "$CONTENT"
}

# Sends a chunked response divided at FIRST_CHUNK
# https://en.wikipedia.org/wiki/Chunked_transfer_encoding
chunk_content() {
    local FIRST_CHUNK="$1"
    local CONTENT="$2"

    local TAIL=$((FIRST_CHUNK+1))

    echo "Transfer-Encoding: chunked"
    echo ""

    printf "%x" $FIRST_CHUNK # First part
    echo "$CR"
    echo "$CONTENT" | head -c $FIRST_CHUNK
    echo "$CR"
    
    printf '%x' $(tail -c +$TAIL <<< "$CONTENT" | wc -c) # The rest of the bytes
    echo "$CR"
    tail -c +$TAIL <<< "$CONTENT"
    echo "$CR"
    
    echo "0$CR" # 0 signals end
    echo "$CR"    
}


# Writes CONTENT as a gzip stream to stdout
gcontent() {
    gzip -f <<< "$CONTENT"
}

answer() {
    # Date: https://tools.ietf.org/html/rfc7231#section-7.1.1.1
    cat <<EOF
HTTP/1.1 200 OK
Server: chunkyweb.sh
Connection: close
Date: $(LC_ALL=C date +"%a, %d %b %Y %H:%M:%S %Z")
EOF
    
    case "$REQUEST" in
        /plain)
            echo "Content-Type: text/plain; charset=utf-8"
            direct_content "$CONTENT"
            ;;
        /chunked)
            echo "Content-Type: text/plain; charset=utf-8"
            chunk_content 1 "$CONTENT"
            ;;
        /gzip)
            echo "Content-Type: text/plain; charset=utf-8"
            echo "Content-Encoding: gzip"
            echo "Content-Length: $(gcontent | wc -c)"
            echo ""
            gcontent
            ;;
        /chunked_and_gzip)
            # We cannot use the chunk_content methods af binaries does not
            # process well as variable content that is echoed
            echo "Content-Type: text/plain; charset=utf-8"
            echo "Content-Encoding: gzip"
            echo "Transfer-Encoding: chunked"
            echo ""
            
            echo "02$CR" # First byte from the content
            gcontent | head -c 2
            echo "$CR"
            
            printf '%x' $(gcontent | tail -c +3 | wc -c) # The rest of the bytes
            echo "$CR"
            gcontent | tail -c +3
            echo "$CR"
            
            echo "0$CR" # 0 signals end
            echo "$CR"
            ;;
        *)
            # TODO: Make this HTML with links
            echo "Content-Type: text/html; charset=utf-8"
            chunk_content 622 "<html>
<head>
<title>chunkyweb</title>
</head>
<body>
<h1>chunkyweb</h1>
<p>A minimal webserver to test web harvesting against chunked and/or compressed content. The logical content delivered from all links below is exactly the same: The text \"<code>$CONTENT</code>\" without the quotes. The only difference is in the delivery.</p>

<p>Note that this page is also delivered as chunked, with a split in the <code>&lt;a href=\"/plain\"&gt;/plain&lt;/a&gt;</code> below: If the link follower does not support chunking, the link to the <em>plain</em> content will not be followed.

<ul>
<li><a href="/plain">/plain</a></li>
<li><a href="/chunked">/chunked</a></li>
<li><a href="/gzip">/gzip</a></li>
<li><a href="/chunked_and_gzip">/chunked_and_gzip</a></li>
</ul>
</html>"
            ;;

    esac
}

echo "Starting chunkyweb. Please visit http://localhost:$PORT/"

rm -f out
mkfifo out
trap "rm -f out" EXIT
while true
do
  cat out | nc -l $PORT > >( # parse the netcat output, to build the answer redirected to the pipe "out".
    export REQUEST=
    while read -r line
    do
      line=$(echo "$line" | tr -d '\r\n')
      >&2 echo "$line"
      if echo "$line" | grep -qE '^GET /' # if line starts with "GET /"
      then
          REQUEST=$(echo "$line" | cut -d ' ' -f2) # extract the request
      elif [ -z "$line" ] # empty line / end of request
      then
        # call a script here
          # Note: REQUEST is exported, so the script can parse it (to answer 200/403/404 status code + content)
          answer > out
      fi
    done
  )
done
