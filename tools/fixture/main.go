// Copyright (c) 2026 Solafon. MIT license; local synthetic test server only.
package main

import (
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	_ "github.com/xtls/xray-core/main/distro/all"
)

func main() {
	if len(os.Args) != 2 { panic("expected local fixture configuration path") }
	file, err := os.Open(os.Args[1]); if err != nil { panic(err) }
	config, err := serial.LoadJSONConfig(file); file.Close(); if err != nil { panic(err) }
	server, err := core.New(config); if err != nil { panic(err) }
	if err := server.Start(); err != nil { panic(err) }
	defer server.Close()
	fmt.Println("QWIP TLS fixture ready")
	stop := make(chan os.Signal, 1); signal.Notify(stop, os.Interrupt, syscall.SIGTERM); <-stop
}
